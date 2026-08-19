package in.sandesh.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Somebody who still works here and may not use the messenger.
 *
 * <p>Distinct from deactivating them in Nirman, which already closes this — {@code
 * chat_directory_v} carries {@code is_active} and {@code session_epoch}, so a deactivated
 * account cannot authenticate here either. This is the lighter, chat-only act.</p>
 */
@Entity
@Table(name = "chat_restriction")
public class ChatRestriction {

    public enum Level {
        /** May read; may not send. The right answer far more often than a block. */
        MUTED,
        /** May not connect at all. */
        BLOCKED
    }

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "level", nullable = false, length = 10)
    private String level;

    @Column(name = "reason", nullable = false, length = 400)
    private String reason;

    @Column(name = "restricted_by", nullable = false)
    private UUID restrictedBy;

    @Column(name = "restricted_at", nullable = false)
    private Instant restrictedAt = Instant.now();

    @Column(name = "until")
    private Instant until;

    protected ChatRestriction() {
    }

    public ChatRestriction(UUID userId, UUID orgId, Level level, String reason, UUID restrictedBy,
                           Instant until) {
        this.userId = userId;
        this.orgId = orgId;
        this.level = level.name();
        this.reason = reason;
        this.restrictedBy = restrictedBy;
        this.until = until;
    }

    public Level getLevel() {
        return Level.valueOf(level);
    }

    public String getReason() {
        return reason;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getUntil() {
        return until;
    }

    /** A restriction with a date on it stops mattering on its own. */
    public boolean isActiveAt(Instant now) {
        return until == null || until.isAfter(now);
    }
}
