package in.sandesh.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Absent means the defaults, which are the ones somebody would choose. */
@Entity
@Table(name = "notify_settings")
public class NotifySettings {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "previews_enabled", nullable = false)
    private boolean previewsEnabled = true;

    @Column(name = "quiet_from")
    private LocalTime quietFrom;

    @Column(name = "quiet_to")
    private LocalTime quietTo;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "muted_conv_ids", nullable = false)
    private String[] mutedConvIds = new String[0];

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected NotifySettings() {
    }

    public NotifySettings(UUID userId) {
        this.userId = userId;
    }

    public boolean isPreviewsEnabled() {
        return previewsEnabled;
    }

    public Set<String> getMutedConvIds() {
        return new LinkedHashSet<>(Arrays.asList(mutedConvIds));
    }

    public boolean isMuted(String convId) {
        return Arrays.asList(mutedConvIds).contains(convId);
    }

    /**
     * Handles a window that wraps midnight, which is the shape almost everybody picks — 22:00 to
     * 07:00 is two ranges, not one, and comparing it as a single interval silently means "never".
     */
    public boolean isQuietAt(LocalTime now) {
        if (quietFrom == null || quietTo == null) {
            return false;
        }
        return quietFrom.isBefore(quietTo)
                ? !now.isBefore(quietFrom) && now.isBefore(quietTo)
                : !now.isBefore(quietFrom) || now.isBefore(quietTo);
    }

    public void update(boolean previewsEnabled, LocalTime quietFrom, LocalTime quietTo,
                       Set<String> muted) {
        this.previewsEnabled = previewsEnabled;
        this.quietFrom = quietFrom;
        this.quietTo = quietTo;
        this.mutedConvIds = muted.toArray(new String[0]);
        this.updatedAt = Instant.now();
    }

    public LocalTime getQuietFrom() {
        return quietFrom;
    }

    public LocalTime getQuietTo() {
        return quietTo;
    }
}
