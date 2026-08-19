package in.sandesh.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One browser on one device that has agreed to be woken. */
@Entity
@Table(name = "push_subscription")
public class PushSubscription {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "endpoint", nullable = false, updatable = false)
    private String endpoint;

    @Column(name = "p256dh", nullable = false, length = 255)
    private String p256dh;

    @Column(name = "auth", nullable = false, length = 255)
    private String auth;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_ok_at")
    private Instant lastOkAt;

    protected PushSubscription() {
    }

    public PushSubscription(UUID userId, String endpoint, String p256dh, String auth,
                            String userAgent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.userAgent = userAgent;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public Instant getLastOkAt() {
        return lastOkAt;
    }

    /** A device that re-subscribes on the same endpoint is the same device with fresh keys. */
    public void refresh(String p256dh, String auth, String userAgent) {
        this.p256dh = p256dh;
        this.auth = auth;
        this.userAgent = userAgent;
    }

    public void succeeded() {
        this.lastOkAt = Instant.now();
    }
}
