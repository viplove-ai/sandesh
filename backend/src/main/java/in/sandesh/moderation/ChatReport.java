package in.sandesh.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A member handing a message to the administrators.
 *
 * <p>The one place a message body is kept — and it is kept because somebody deliberately handed
 * it over, which is a different act from the server retaining it behind their back.</p>
 */
@Entity
@Table(name = "chat_report")
public class ChatReport {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private UUID reporterId;

    @Column(name = "subject_id", updatable = false)
    private UUID subjectId;

    @Column(name = "conv_id", length = 120, updatable = false)
    private String convId;

    @Column(name = "quoted_body", updatable = false)
    private String quotedBody;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ChatReport() {
    }

    public ChatReport(UUID orgId, UUID reporterId, UUID subjectId, String convId,
                      String quotedBody, String note) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.reporterId = reporterId;
        this.subjectId = subjectId;
        this.convId = convId;
        this.quotedBody = quotedBody;
        this.note = note;
    }

    public UUID getId() {
        return id;
    }
}
