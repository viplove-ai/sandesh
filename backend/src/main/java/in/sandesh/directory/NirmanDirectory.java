package in.sandesh.directory;

import java.util.List;
import java.util.UUID;

/**
 * Everything Sandesh is allowed to know about Nirman, and the whole of it.
 *
 * <p>Backed by two views — {@code chat_directory_v} and {@code chat_site_membership_v} — read
 * through a role with {@code SELECT} and nothing else. Answers are cached for 120 seconds, which
 * is the authorisation window written into the plan: a revoked assignment closes membership
 * within two minutes, and paying for a query per request is the alternative.</p>
 */
public interface NirmanDirectory {

    record Person(UUID userId, UUID orgId, String fullName, String username) {
    }

    record Membership(UUID siteId, UUID projectId, String siteName, String projectName) {
    }

    /** True when the account is live and the token's epoch still matches the stored one. */
    boolean isSessionCurrent(UUID userId, long sessionEpoch);

    /** The sites this person is posted to today. Empty for head office, and that is normal. */
    List<Membership> membershipsOf(UUID userId);

    /** Everyone posted to the site today — the site conversation's member list. */
    List<Person> membersOfSite(UUID siteId);

    List<Person> lookUp(List<UUID> userIds);

    /** Org-wide directory search, because a person with no posting still has colleagues. */
    List<Person> search(UUID orgId, String query, int limit);
}
