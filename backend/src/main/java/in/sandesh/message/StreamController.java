package in.sandesh.message;

import in.sandesh.message.MessageDtos.Delivery;
import in.sandesh.security.AuthenticatedUser;
import in.sandesh.moderation.RestrictionGuard;
import in.sandesh.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one held connection, and it is Server-Sent Events rather than a WebSocket.
 *
 * <p>A phone's connection dies constantly — backgrounding freezes the page, a handover between
 * towers drops the socket, a valley takes it away without a FIN. So the transport was chosen for
 * how well it breaks rather than how well it holds: {@code EventSource} reconnects on its own and
 * sends {@code Last-Event-ID}, which is exactly the "what did I miss" question the outbox
 * answers. With a WebSocket that reconnect logic is the most bug-prone code in the client.</p>
 *
 * <p>The connection is an optimisation. Delivery is guaranteed by the outbox and, from Phase 3,
 * by push — never by this being open.</p>
 */
@RestController
@RequestMapping("/api/v1/stream")
@Tag(name = "Stream", description = "Server-sent events; resumes with Last-Event-ID")
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    private final MessageService messages;
    private final StreamRegistry streams;
    private final CurrentUser currentUser;
    private final RestrictionGuard restrictions;
    private final long maxMinutes;
    private final long heartbeatSeconds;
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public StreamController(MessageService messages, StreamRegistry streams, CurrentUser currentUser,
                            RestrictionGuard restrictions,
                            @Value("${app.stream.max-minutes:10}") long maxMinutes,
                            @Value("${app.stream.heartbeat-seconds:25}") long heartbeatSeconds) {
        this.messages = messages;
        this.streams = streams;
        this.currentUser = currentUser;
        this.restrictions = restrictions;
        this.maxMinutes = maxMinutes;
        this.heartbeatSeconds = heartbeatSeconds;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Open the message stream and replay anything this device has not acked")
    public SseEmitter open(@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        AuthenticatedUser user = currentUser.required();
        restrictions.assertMayConnect(user.userId());

        // Deliberately finite. The authorisation snapshot is taken when the connection opens, so
        // capping its life is what bounds how long a revoked assignment can keep receiving —
        // the client reconnects immediately and is re-checked.
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(maxMinutes));
        streams.register(user.userId(), emitter);

        try {
            emitter.send(SseEmitter.event().name("ready").data(new Ready(user.userId(),
                    Instant.now(), messages.pendingCount(user.userId()))));
            replay(user.userId(), lastEventId, emitter);
        } catch (IOException closed) {
            // The client hung up between the request and the first write. Nothing is lost.
            emitter.completeWithError(closed);
            return emitter;
        }

        scheduleHeartbeat(user.userId(), emitter);
        return emitter;
    }

    /**
     * Everything after the last event this device saw. {@code Last-Event-ID} is sent by the
     * browser itself on reconnect, so this needs no cooperation from the client beyond having
     * been given ids in the first place.
     */
    private void replay(UUID userId, String lastEventId, SseEmitter emitter) throws IOException {
        Instant afterSentAt = null;
        UUID afterMsgId = null;
        if (lastEventId != null && lastEventId.contains("-")) {
            try {
                int split = lastEventId.indexOf('-');
                afterSentAt = Instant.ofEpochMilli(Long.parseLong(lastEventId.substring(0, split)));
                afterMsgId = UUID.fromString(lastEventId.substring(split + 1));
            } catch (RuntimeException unparseable) {
                // A malformed header replays everything rather than nothing: a duplicate is
                // deduped on the device, a gap is a message somebody never sees.
                log.debug("Unparseable Last-Event-ID {}; replaying in full", lastEventId);
            }
        }
        List<Delivery> pending = messages.pendingFor(userId, afterSentAt, afterMsgId);
        for (Delivery delivery : pending) {
            emitter.send(SseEmitter.event()
                    .id(delivery.sentAt().toEpochMilli() + "-" + delivery.msgId())
                    .name("message")
                    .data(delivery));
        }
    }

    /**
     * A comment line every 25 seconds. It is not a keep-alive for the client's sake — it is how
     * this process discovers that a phone went into a tunnel, because a dead TCP connection
     * looks exactly like an idle one until something is written to it.
     */
    private void scheduleHeartbeat(UUID userId, SseEmitter emitter) {
        var handle = heartbeats.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException gone) {
                streams.remove(userId, emitter);
                emitter.complete();
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);

        emitter.onCompletion(() -> handle.cancel(false));
        emitter.onTimeout(() -> {
            handle.cancel(false);
            emitter.complete();
        });
        emitter.onError(e -> handle.cancel(false));
    }

    private record Ready(UUID userId, Instant serverTime, long pending) {
    }
}
