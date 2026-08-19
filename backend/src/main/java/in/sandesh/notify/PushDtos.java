package in.sandesh.notify;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalTime;
import java.util.Set;

public final class PushDtos {

    public record SubscribeRequest(@NotBlank String endpoint, @NotBlank String p256dh,
                                   @NotBlank String auth) {
    }

    public record SettingsView(boolean previewsEnabled, LocalTime quietFrom, LocalTime quietTo,
                               Set<String> mutedConvIds) {
    }

    public record SettingsRequest(boolean previewsEnabled, LocalTime quietFrom, LocalTime quietTo,
                                  Set<String> mutedConvIds) {
    }

    /**
     * What the Notification Health screen reads.
     *
     * <p>Android OEM battery management is the biggest single threat to this app being useful,
     * and it presents to the user as "I just don't get them". So the app has to be able to say
     * what it actually knows — is push configured at all, does the server have a device
     * registered for me — rather than leaving support to diagnose it over the telephone.</p>
     */
    public record HealthView(boolean pushConfiguredOnServer, int registeredDevices,
                             String vapidPublicKey) {
    }

    private PushDtos() {
    }
}
