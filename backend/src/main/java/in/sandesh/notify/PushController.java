package in.sandesh.notify;

import in.sandesh.notify.PushDtos.HealthView;
import in.sandesh.notify.PushDtos.SettingsRequest;
import in.sandesh.notify.PushDtos.SettingsView;
import in.sandesh.notify.PushDtos.SubscribeRequest;
import in.sandesh.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/push")
@Tag(name = "Push", description = "Subscriptions, preferences, and the notification health check")
public class PushController {

    private final PushSubscriptionRepository subscriptions;
    private final NotifySettingsRepository settings;
    private final WebPushNotifier notifier;
    private final VapidProperties vapid;
    private final CurrentUser currentUser;

    public PushController(PushSubscriptionRepository subscriptions,
                          NotifySettingsRepository settings, WebPushNotifier notifier,
                          VapidProperties vapid, CurrentUser currentUser) {
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.notifier = notifier;
        this.vapid = vapid;
        this.currentUser = currentUser;
    }

    @PostMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Register this browser to be woken")
    public void subscribe(@Valid @RequestBody SubscribeRequest request, HttpServletRequest http) {
        UUID userId = currentUser.id();
        // Keyed by endpoint, because a browser re-subscribing on the same endpoint is the same
        // device with rotated keys — not a second device. Without this every permission prompt
        // and every key rotation leaves a dead row that is pushed to forever.
        subscriptions.findByEndpoint(request.endpoint())
                .ifPresentOrElse(
                        existing -> {
                            existing.refresh(request.p256dh(), request.auth(),
                                    http.getHeader("User-Agent"));
                            subscriptions.save(existing);
                        },
                        () -> subscriptions.save(new PushSubscription(userId, request.endpoint(),
                                request.p256dh(), request.auth(), http.getHeader("User-Agent"))));
    }

    @DeleteMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Stop notifying every device of the caller's")
    public void unsubscribe() {
        subscriptions.deleteByUserId(currentUser.id());
    }

    @GetMapping("/settings")
    @Operation(summary = "Preview, quiet hours and muted conversations")
    public SettingsView readSettings() {
        return settings.findById(currentUser.id())
                .map(s -> new SettingsView(s.isPreviewsEnabled(), s.getQuietFrom(), s.getQuietTo(),
                        s.getMutedConvIds()))
                .orElseGet(() -> new SettingsView(true, null, null, Set.of()));
    }

    @PutMapping("/settings")
    @Transactional
    @Operation(summary = "Change them")
    public SettingsView writeSettings(@RequestBody SettingsRequest request) {
        UUID userId = currentUser.id();
        NotifySettings row = settings.findById(userId).orElseGet(() -> new NotifySettings(userId));
        row.update(request.previewsEnabled(), request.quietFrom(), request.quietTo(),
                request.mutedConvIds() == null ? Set.of() : request.mutedConvIds());
        settings.save(row);
        return new SettingsView(row.isPreviewsEnabled(), row.getQuietFrom(), row.getQuietTo(),
                row.getMutedConvIds());
    }

    @GetMapping("/health")
    @Operation(summary = "What the server knows about whether this person can be reached")
    public HealthView health() {
        return new HealthView(notifier.isEnabled(),
                subscriptions.findByUserId(currentUser.id()).size(),
                vapid.configured() ? vapid.publicKey() : null);
    }

    /**
     * Sends one to the caller, right now.
     *
     * <p>The single most useful thing on the health screen. "I don't get notifications" has half
     * a dozen causes — permission never granted, the OEM's battery saver, restricted background
     * data, an expired subscription — and a button that either produces a notification or does
     * not is how the user and support tell which half of the chain is broken.</p>
     */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Send a test notification to the caller's own devices")
    public void test() {
        notifier.notify(currentUser.id(), new Notifier.Notification(
                "Sandesh", "Notifications are working on this phone.", "/", "test", -1));
    }
}
