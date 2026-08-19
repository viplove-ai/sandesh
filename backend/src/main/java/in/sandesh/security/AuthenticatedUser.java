package in.sandesh.security;

import java.util.Set;
import java.util.UUID;

/**
 * Built entirely from Nirman's access-token claims. Two of them are re-checked against the
 * Nirman database on the way through, because both can change inside a token's fifteen-minute
 * life: the session epoch, and the site list.
 *
 * @param sessionEpoch the value {@code users.session_epoch} held when the token was issued, or
 *                     {@link #NO_SESSION_EPOCH} for a token minted before the claim existed —
 *                     which matches no account and so is refused
 */
public record AuthenticatedUser(
        UUID userId,
        UUID orgId,
        String username,
        Set<String> roles,
        Set<String> permissions,
        Set<UUID> siteIds,
        boolean allSites,
        long sessionEpoch) {

    public static final long NO_SESSION_EPOCH = -1L;

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }

    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }
}
