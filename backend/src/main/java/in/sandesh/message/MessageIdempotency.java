package in.sandesh.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The ledger that makes a re-send free.
 *
 * <p>The id is minted on the device, so a phone on a bad link can send the same message three
 * times without producing three messages. Carries no body: it records that a client id was
 * already accepted and what it was answered with, and nothing about what was said.</p>
 */
@Entity
@Table(name = "message_idempotency")
public class MessageIdempotency {

    @Id
    @Column(name = "client_msg_id", nullable = false, updatable = false)
    private UUID clientMsgId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    @Column(name = "msg_id", nullable = false, updatable = false)
    private UUID msgId;

    @Column(name = "conv_id", nullable = false, length = 120, updatable = false)
    private String convId;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected MessageIdempotency() {
    }

    public MessageIdempotency(UUID clientMsgId, UUID senderId, UUID msgId, String convId,
                              Instant sentAt) {
        this.clientMsgId = clientMsgId;
        this.senderId = senderId;
        this.msgId = msgId;
        this.convId = convId;
        this.sentAt = sentAt;
    }

    public UUID getMsgId() {
        return msgId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
