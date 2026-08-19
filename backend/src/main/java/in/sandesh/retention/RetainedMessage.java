package in.sandesh.retention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A message in a work channel, kept because the record is the argument.
 *
 * <p>In construction, who told whom to do what and when decides extension-of-time claims,
 * defect liability and arbitration. Tier 2 is the most valuable data this company generates and
 * a seven-day spool would throw it away.</p>
 *
 * <p>Never a direct message. That is enforced here, in the service, and by a check constraint —
 * three times, because it is the one thing that must not be got wrong.</p>
 */
@Entity
@Table(name = "retained_message")
public class RetainedMessage {

    @Id
    @Column(name = "msg_id", nullable = false, updatable = false)
    private UUID msgId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "conv_id", nullable = false, length = 120, updatable = false)
    private String convId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private String kind;

    @Column(name = "body", updatable = false)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media", updatable = false)
    private String media;

    /** The buttons on a system card. Null for every ordinary message. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions", updatable = false)
    private String actions;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "retain_until", nullable = false, updatable = false)
    private Instant retainUntil;

    protected RetainedMessage() {
    }

    public RetainedMessage(UUID msgId, UUID orgId, String convId, UUID senderId, String kind,
                           String body, String media, Instant sentAt, Instant retainUntil) {
        this.msgId = msgId;
        this.orgId = orgId;
        this.convId = convId;
        this.senderId = senderId;
        this.kind = kind;
        this.body = body;
        this.media = media;
        this.sentAt = sentAt;
        this.retainUntil = retainUntil;
    }

    public UUID getMsgId() {
        return msgId;
    }

    public String getConvId() {
        return convId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public String getKind() {
        return kind;
    }

    public String getBody() {
        return body;
    }

    public String getMedia() {
        return media;
    }

    public String getActions() {
        return actions;
    }

    public void setActions(String actions) {
        this.actions = actions;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
