package in.sandesh.moderation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ModerationDtos {

    public record RestrictRequest(@NotNull UUID userId, @NotNull ChatRestriction.Level level,
                                  @NotBlank @Size(max = 400) String reason, Instant until) {
    }

    public record RestrictionView(UUID userId, String fullName, String level, String reason,
                                  Instant restrictedAt, Instant until) {
    }

    public record ReportRequest(UUID subjectId, String convId, @Size(max = 4000) String quotedBody,
                                @Size(max = 1000) String note) {
    }

    public record AuditView(UUID actorId, String actorName, String action, UUID subjectId,
                            Instant at) {
    }

    private ModerationDtos() {
    }
}
