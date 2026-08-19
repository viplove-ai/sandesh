package in.sandesh.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One undelivered message, for one recipient.
 *
 * <p>Deleted the moment that recipient's device says it has committed the message to its own
 * storage. What survives here for more than a few seconds is only what is owed to a phone that
 * is switched off — and after seven days, not even that.</p>
 */
@Entity
@Table(name = "outbox")
public class OutboxEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "conv_id", nullable = false, length = 120, updatable = false)
    private String convId;

    @Column(name = "msg_id", nullable = false, updatable = false)
    private UUID msgId;

    @Column(name = "client_msg_id", nullable = false, updatable = false)
    private UUID clientMsgId;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OutboxEntry() {
    }

    public OutboxEntry(UUID recipientId, UUID senderId, UUID orgId, String convId, UUID msgId,
                       UUID clientMsgId, String kind, String body, String media, Instant sentAt) {
        this.id = UUID.randomUUID();
        this.recipientId = recipientId;
        this.senderId = senderId;
        this.orgId = orgId;
        this.convId = convId;
        this.msgId = msgId;
        this.clientMsgId = clientMsgId;
        this.kind = kind;
        this.body = body;
        this.media = media;
        this.sentAt = sentAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public String getConvId() {
        return convId;
    }

    public UUID getMsgId() {
        return msgId;
    }

    public UUID getClientMsgId() {
        return clientMsgId;
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
