package in.sandesh.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Security;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Web Push over VAPID.
 *
 * <p><b>Payload-first, and that is not a style choice.</b> iOS forbids a push that produces no
 * visible notification — violate it a few times and the subscription is revoked — which kills
 * the otherwise attractive "silent ping, then fetch the spool" design. So the whole notification
 * is rendered from this payload, and the service worker's attempt to sync afterwards is a bonus
 * rather than the mechanism.</p>
 *
 * <p>The payload is encrypted to the browser's own key before it leaves here, so Google and
 * Apple carry it without being able to read it. That is what makes a preview safe to include —
 * and the preview is still switchable per user, because "R. Negi: the beam is cracked" on a
 * lock screen is somebody else's business at the wrong moment.</p>
 */
@Service
public class WebPushNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(WebPushNotifier.class);

    static {
        // web-push needs the BC provider for the ECDH the payload encryption is built on.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final PushSubscriptionRepository subscriptions;
    private final NotifySettingsRepository settings;
    private final VapidProperties vapid;
    private final ObjectMapper json;
    private final ZoneId zone;
    private final PushService pushService;

    public WebPushNotifier(PushSubscriptionRepository subscriptions,
                           NotifySettingsRepository settings, VapidProperties vapid,
                           ObjectMapper json,
                           @org.springframework.beans.factory.annotation.Value("${app.timezone:Asia/Kolkata}")
                           String timezone) {
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.vapid = vapid;
        this.json = json;
        this.zone = ZoneId.of(timezone);
        this.pushService = buildPushService(vapid);
    }

    private static PushService buildPushService(VapidProperties vapid) {
        if (!vapid.configured()) {
            // Push is off until somebody generates a key pair. The pilot has to be able to run
            // before that, and a service that will not start without one cannot be tried.
            log.warn("Push notifications are disabled: app.push.public-key/private-key are unset");
            return null;
        }
        try {
            PushService service = new PushService();
            service.setPublicKey(vapid.publicKey());
            service.setPrivateKey(vapid.privateKey());
            service.setSubject(vapid.subject());
            return service;
        } catch (Exception e) {
            log.error("Push notifications are disabled: the VAPID key pair could not be read", e);
            return null;
        }
    }

    public boolean isEnabled() {
        return pushService != null;
    }

    /**
     * Never throws. A message is already durable in the outbox by the time this runs, and a push
     * service having a bad afternoon must not roll back a send that succeeded.
     */
    @Override
    @Transactional
    public void notify(UUID userId, Notifier.Notification notification) {
        if (pushService == null) {
            return;
        }
        NotifySettings preference = settings.findById(userId).orElse(null);
        if (preference != null) {
            if (preference.isMuted(notification.tag())) {
                return;
            }
            if (preference.isQuietAt(LocalTime.now(zone))) {
                return;
            }
        }

        boolean previews = preference == null || preference.isPreviewsEnabled();
        String body = previews ? notification.body() : "New message";

        List<PushSubscription> devices = subscriptions.findByUserId(userId);
        for (PushSubscription device : devices) {
            send(device, notification, body);
        }
    }

    private void send(PushSubscription device, Notifier.Notification notification, String body) {
        try {
            String payload = json.writeValueAsString(Map.of(
                    "title", notification.title(),
                    "body", body,
                    "deepLink", notification.deepLink(),
                    "tag", notification.tag(),
                    "badge", notification.badge()));

            HttpResponse response = pushService.send(new nl.martijndwars.webpush.Notification(
                    new Subscription(device.getEndpoint(),
                            new Subscription.Keys(device.getP256dh(), device.getAuth())),
                    payload));

            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                // Gone for good: the browser dropped the subscription, or the app was
                // uninstalled. These two codes and only these two — deleting on a 503 throws
                // away a working subscription during somebody else's outage.
                log.info("Pruning a subscription the push service says is gone ({})", status);
                subscriptions.delete(device);
            } else if (status >= 200 && status < 300) {
                device.succeeded();
                subscriptions.save(device);
            } else {
                log.warn("Push refused with {} — keeping the subscription", status);
            }
        } catch (Exception e) {
            log.warn("Could not push to a device; the message is already in the outbox", e);
        }
    }
}
