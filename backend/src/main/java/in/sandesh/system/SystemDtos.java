package in.sandesh.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class SystemDtos {

    /**
     * One button.
     *
     * @param path a path on Nirman's own API — {@code /expenses/{id}/approve} — and never a full
     *             URL. The device joins it to the Nirman base it already knows, so a card cannot
     *             point a signed-in phone at another host
     * @param confirm text to show before acting, for anything that cannot be undone
     */
    public record Action(@NotBlank String label, @NotBlank String method, @NotBlank String path,
                         String confirm, boolean primary) {
    }

    public record NotifyRequest(
            @NotNull UUID userId,
            @NotNull UUID orgId,
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 500) String body,
            /** Where tapping the card itself goes, inside Nirman. */
            String deepLink,
            /** Collapses an earlier card about the same record — an approval handled elsewhere. */
            String tag,
            List<Action> actions) {
    }

    private SystemDtos() {
    }
}
