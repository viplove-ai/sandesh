package in.sandesh.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is connected, right now, to this instance.
 *
 * <p>In the JVM and nowhere else. Presence is per-instance state about connections this process
 * is holding — it cannot outlive the process it describes, so there is nothing to persist and
 * nothing to share. A heartbeat that costs a database write is the one thing at this scale that
 * would actually hurt.</p>
 *
 * <p>One instance is the deployment this is written for. A second would need the deliveries here
 * published to it, and the seam for that is this class rather than the caller.</p>
 */
@Component
public class StreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamRegistry.class);

    private final Map<UUID, Set<SseEmitter>> byUser = new ConcurrentHashMap<>();

    public void register(UUID userId, SseEmitter emitter) {
        byUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));
    }

    public void remove(UUID userId, SseEmitter emitter) {
        Set<SseEmitter> emitters = byUser.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            // Do not leave an empty set behind: this map is keyed by every user who has ever
            // connected, and without this it is a slow leak rather than a fast one.
            byUser.remove(userId, Set.of());
            if (emitters.isEmpty()) {
                byUser.remove(userId, emitters);
            }
        }
    }

    public boolean isConnected(UUID userId) {
        Set<SseEmitter> emitters = byUser.get(userId);
        return emitters != null && !emitters.isEmpty();
    }

    /**
     * @return true if at least one live connection took it. False means the recipient is not here,
     *         and the caller should notify them instead — the message is already durable in the
     *         outbox either way.
     */
    public boolean push(UUID userId, String eventId, Object payload) {
        Set<SseEmitter> emitters = byUser.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return false;
        }
        boolean delivered = false;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().id(eventId).name("message").data(payload));
                delivered = true;
            } catch (IOException | IllegalStateException gone) {
                // The phone went into a tunnel. Nothing is lost: the outbox still holds it and
                // the device will ask again with Last-Event-ID when it comes back.
                log.debug("Dropping dead emitter for {}", userId);
                remove(userId, emitter);
            }
        }
        return delivered;
    }

    public int connectionCount() {
        return byUser.values().stream().mapToInt(Set::size).sum();
    }
}
