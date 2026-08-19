package in.sandesh.notify;

import java.util.UUID;

/**
 * Reaching a person who is not looking at the app.
 *
 * <p>Deliberately generic and deliberately not about chat. Nirman has no notification mechanism
 * of any kind — no push, no VAPID keys, no service-worker handler — so this is written as the
 * thing both products will call rather than as a private detail of the messenger. Six months
 * from now Nirman asks it to say "your expense was approved" and none of this is built twice.</p>
 */
public interface Notifier {

    /**
     * @param tag  collapses an earlier notification with the same tag, so twenty messages in a
     *             site channel produce one line reading "20 new messages" and not twenty
     * @param badge what the app icon should show; -1 to leave it alone
     */
    record Notification(String title, String body, String deepLink, String tag, int badge) {
    }

    /** Best-effort and never throws: a notification that cannot be sent must not fail a write. */
    void notify(UUID userId, Notification notification);
}
