package in.sandesh.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * What an administrator did, readable by every administrator in the organisation and not only
 * the one who did it. An admin acting quietly leaves a trace they cannot remove.
 */
@Entity
@Table(name = "chat_audit")
public class ChatAudit {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 40, updatable = false)
    private String action;

    @Column(name = "subject_id", updatable = false)
    private UUID subjectId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", updatable = false)
    private String detail;

    @Column(name = "at", nullable = false, updatable = false)
    private Instant at = Instant.now();

    protected ChatAudit() {
    }

    public ChatAudit(UUID orgId, UUID actorId, String action, UUID subjectId, String detail) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.actorId = actorId;
        this.action = action;
        this.subjectId = subjectId;
        this.detail = detail;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public Instant getAt() {
        return at;
    }
}
