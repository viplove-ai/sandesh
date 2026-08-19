package in.sandesh.directory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The one place that touches Nirman's database. Plain JDBC against the views — never JPA, never
 * a repository, and never a write.
 */
@Service
public class NirmanDirectoryService implements NirmanDirectory {

    /**
     * The authorisation window. Two minutes, and it is a decision rather than a default: a
     * database round trip per typed character is the alternative, and Nirman's own guarantee
     * (revoked at 10:00 does not survive a token issued at 09:58) is bought with one lookup per
     * HTTP request, which a long-lived stream does not have.
     */
    private static final Duration WINDOW = Duration.ofSeconds(120);

    private static final RowMapper<Person> PERSON = (rs, i) -> new Person(
            rs.getObject("user_id", UUID.class), rs.getObject("org_id", UUID.class),
            rs.getString("full_name"), rs.getString("username"));

    private static final RowMapper<Membership> MEMBERSHIP = (rs, i) -> new Membership(
            rs.getObject("site_id", UUID.class), rs.getObject("project_id", UUID.class),
            rs.getString("site_name"), rs.getString("project_name"));

    private final JdbcTemplate jdbc;
    private final Cache<UUID, List<Membership>> membershipCache =
            Caffeine.newBuilder().expireAfterWrite(WINDOW).maximumSize(10_000).build();
    private final Cache<UUID, List<Person>> siteMemberCache =
            Caffeine.newBuilder().expireAfterWrite(WINDOW).maximumSize(5_000).build();

    public NirmanDirectoryService(JdbcTemplate nirmanJdbc) {
        this.jdbc = nirmanJdbc;
    }

    /**
     * Deliberately uncached. A cache here would put a window back exactly where the session epoch
     * exists to close one, and the difference between "signed out now" and "signed out in two
     * minutes" is the whole point of the counter.
     */
    @Override
    public boolean isSessionCurrent(UUID userId, long sessionEpoch) {
        if (userId == null || sessionEpoch < 0) {
            return false;
        }
        try {
            Long current = jdbc.queryForObject(
                    "SELECT session_epoch FROM chat_directory_v WHERE user_id = ? AND is_active = true",
                    Long.class, userId);
            return current != null && current == sessionEpoch;
        } catch (EmptyResultDataAccessException deactivated) {
            return false;
        }
    }

    @Override
    public List<Membership> membershipsOf(UUID userId) {
        return membershipCache.get(userId, id -> jdbc.query("""
                SELECT DISTINCT site_id, project_id, site_name, project_name
                  FROM chat_site_membership_v WHERE user_id = ?
                 ORDER BY project_name, site_name
                """, MEMBERSHIP, id));
    }

    @Override
    public List<Person> membersOfSite(UUID siteId) {
        return siteMemberCache.get(siteId, id -> jdbc.query("""
                SELECT d.user_id, d.org_id, d.full_name, d.username
                  FROM chat_site_membership_v m
                  JOIN chat_directory_v d ON d.user_id = m.user_id
                 WHERE m.site_id = ? AND d.is_active = true
                 ORDER BY d.full_name
                """, PERSON, id));
    }

    @Override
    public List<Person> lookUp(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", userIds.stream().map(u -> "?").toList());
        return jdbc.query("SELECT user_id, org_id, full_name, username FROM chat_directory_v"
                + " WHERE is_active = true AND user_id IN (" + placeholders + ")",
                PERSON, userIds.toArray());
    }

    /**
     * Org-wide, and that is the amended rule from the plan: a new hire, an accountant or an admin
     * has no posting and would otherwise be able to message nobody at all. The list a person sees
     * by default is still only their own sites — search is broad, defaults are narrow.
     */
    @Override
    public List<Person> search(UUID orgId, String query, int limit) {
        return jdbc.query("""
                SELECT user_id, org_id, full_name, username
                  FROM chat_directory_v
                 WHERE org_id = ? AND is_active = true
                   AND (full_name ILIKE ? OR username ILIKE ?)
                 ORDER BY full_name LIMIT ?
                """, PERSON, orgId, "%" + query + "%", "%" + query + "%", limit);
    }
}
