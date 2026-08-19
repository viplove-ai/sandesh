package in.sandesh.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Verify-only half of Nirman's {@code JwtTokenService}. Deliberately a copy rather than a shared
 * library: the claim names are a published contract between two services, and a copy that drifts
 * fails loudly in this service's own tests rather than silently coupling their release cycles.
 *
 * <p>There is no {@code createAccessToken} here and there must never be one. Sandesh issuing a
 * token that Nirman would honour is the authorisation bypass the whole design exists to avoid.</p>
 */
@Service
public class JwtTokenService {

    static final String CLAIM_ORG = "org";
    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_PERMISSIONS = "perms";
    static final String CLAIM_SITES = "sites";
    static final String CLAIM_SESSION_EPOCH = "sep";
    static final String ALL_SITES = "ALL";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** @throws JwtException when the token is forged, malformed or expired. */
    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Object sitesClaim = claims.get(CLAIM_SITES);
        boolean allSites = ALL_SITES.equals(sitesClaim);
        Set<UUID> siteIds = allSites
                ? Set.of()
                : stringSet(sitesClaim).stream().map(UUID::fromString).collect(Collectors.toSet());

        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get(CLAIM_ORG, String.class)),
                claims.get("username", String.class),
                stringSet(claims.get(CLAIM_ROLES)),
                stringSet(claims.get(CLAIM_PERMISSIONS)),
                siteIds,
                allSites,
                sessionEpoch(claims));
    }

    private static long sessionEpoch(Claims claims) {
        Object claim = claims.get(CLAIM_SESSION_EPOCH);
        return claim instanceof Number number
                ? number.longValue()
                : AuthenticatedUser.NO_SESSION_EPOCH;
    }

    private static Set<String> stringSet(Object claim) {
        if (claim instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }
}
