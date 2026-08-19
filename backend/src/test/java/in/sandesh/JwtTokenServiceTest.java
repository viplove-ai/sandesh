package in.sandesh;

import in.sandesh.security.AuthenticatedUser;
import in.sandesh.security.JwtProperties;
import in.sandesh.security.JwtTokenService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The claim names here are a published contract with Nirman. If somebody renames one on that
 * side, this test is what fails — which is the reason the parser is a copy rather than a shared
 * library nobody would notice drifting.
 */
class JwtTokenServiceTest {

    private static final String SECRET = "a-test-secret-that-is-comfortably-over-32-bytes";
    private static final String ISSUER = "nirman";

    private final JwtTokenService tokens =
            new JwtTokenService(new JwtProperties(SECRET, ISSUER));

    @Test
    void readsTheClaimsNirmanWrites() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();

        AuthenticatedUser user = tokens.parse(nirmanToken(userId, orgId,
                List.of(siteId.toString()), 7L));

        assertThat(user.userId()).isEqualTo(userId);
        assertThat(user.orgId()).isEqualTo(orgId);
        assertThat(user.roles()).containsExactly("SUPERVISOR");
        assertThat(user.permissions()).contains("attendance:create");
        assertThat(user.siteIds()).containsExactly(siteId);
        assertThat(user.allSites()).isFalse();
        assertThat(user.sessionEpoch()).isEqualTo(7L);
    }

    @Test
    void theAllSitesClaimIsALiteralAndNotASiteId() {
        AuthenticatedUser user = tokens.parse(
                nirmanToken(UUID.randomUUID(), UUID.randomUUID(), "ALL", 1L));

        assertThat(user.allSites()).isTrue();
        assertThat(user.siteIds()).isEmpty();
    }

    @Test
    void aTokenWithNoSessionEpochMatchesNoAccount() {
        AuthenticatedUser user = tokens.parse(
                nirmanToken(UUID.randomUUID(), UUID.randomUUID(), "ALL", null));

        // Negative, so it can never equal a stored counter — the account is refused rather than
        // being let in on a claim that predates the counter.
        assertThat(user.sessionEpoch()).isEqualTo(AuthenticatedUser.NO_SESSION_EPOCH);
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRefused() {
        SecretKey other = Keys.hmacShaKeyFor(
                "a-completely-different-secret-also-over-32-bytes".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder().subject(UUID.randomUUID().toString()).issuer(ISSUER)
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                .signWith(other).compact();

        assertThatThrownBy(() -> tokens.parse(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() {
        String wrongIssuer = Jwts.builder().subject(UUID.randomUUID().toString())
                .issuer("somebody-else")
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> tokens.parse(wrongIssuer)).isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredTokenIsRefused() {
        String expired = Jwts.builder().subject(UUID.randomUUID().toString()).issuer(ISSUER)
                .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(2))))
                .expiration(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> tokens.parse(expired)).isInstanceOf(JwtException.class);
    }

    /** Built exactly as Nirman's JwtTokenService builds it. */
    private static String nirmanToken(UUID userId, UUID orgId, Object sites, Long sessionEpoch) {
        var builder = Jwts.builder()
                .subject(userId.toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                .claim("org", orgId.toString())
                .claim("username", "rnegi")
                .claim("roles", List.of("SUPERVISOR"))
                .claim("perms", List.of("attendance:create", "site:read"))
                .claim("sites", sites);
        if (sessionEpoch != null) {
            builder.claim("sep", sessionEpoch);
        }
        return builder.signWith(
                Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
