package in.sandesh.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * What was uploaded, so a download can be authorised without trusting the client's claim about
 * which conversation an object belongs to. The bytes live in MinIO under {@code objectKey} with
 * their own lifecycle rule; this row is the reference, not the file.
 */
@Entity
@Table(name = "media_object")
public class MediaObject {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "uploader_id", nullable = false, updatable = false)
    private UUID uploaderId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "conv_id", length = 120, updatable = false)
    private String convId;

    @Column(name = "object_key", nullable = false, length = 200, updatable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected MediaObject() {
    }

    public MediaObject(UUID id, UUID uploaderId, UUID orgId, String convId, String objectKey,
                       String contentType, long sizeBytes, String fileName) {
        this.id = id;
        this.uploaderId = uploaderId;
        this.orgId = orgId;
        this.convId = convId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.fileName = fileName;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public String getFileName() {
        return fileName;
    }
}
