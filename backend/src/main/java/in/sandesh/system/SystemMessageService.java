package in.sandesh.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.sandesh.common.BusinessException;
import in.sandesh.conversation.ConversationId;
import in.sandesh.message.MessageDtos.Delivery;
import in.sandesh.message.OutboxEntry;
import in.sandesh.message.OutboxRepository;
import in.sandesh.message.StreamRegistry;
import in.sandesh.notify.Notifier;
import in.sandesh.system.SystemDtos.Action;
import in.sandesh.system.SystemDtos.NotifyRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Cards posted by Nirman into a person's own channel.
 *
 * <p>The rule that must not be broken: <b>this service never performs a Nirman action.</b> It
 * stores a card and delivers it. Every button on it is a request the device makes to Nirman with
 * the user's own token, so {@code @PreAuthorize}, {@code SiteAccessGuard} and
 * {@code PeriodLockGuard} run exactly as they do from the Nirman app.</p>
 *
 * <p>A service account that approves expenses on somebody's behalf is an authorisation bypass
 * with a friendly name, and it would be the worst thing this project could ship.</p>
 */
@Service
public class SystemMessageService {

    private static final Logger log = LoggerFactory.getLogger(SystemMessageService.class);

    /** Everything a button may ask Nirman to do. A card cannot invent a verb. */
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH");

    private final OutboxRepository outbox;
    private final StreamRegistry streams;
    private final Notifier notifier;
    private final ObjectMapper json;

    public SystemMessageService(OutboxRepository outbox, StreamRegistry streams,
                                Notifier notifier, ObjectMapper json) {
        this.outbox = outbox;
        this.streams = streams;
        this.notifier = notifier;
        this.json = json;
    }

    @Transactional
    public UUID post(NotifyRequest request) {
        List<Action> actions = request.actions() == null ? List.of() : request.actions();
        actions.forEach(SystemMessageService::validate);

        ConversationId conversation = ConversationId.system(request.userId());
        UUID msgId = UUID.randomUUID();
        Instant sentAt = Instant.now();

        OutboxEntry row = new OutboxEntry(request.userId(), request.userId(), request.orgId(),
                conversation.toString(), msgId, UUID.randomUUID(), "SYSTEM",
                request.title() + "\n" + request.body(), null, sentAt);
        applyActions(row, actions);
        outbox.save(row);

        // Same two paths as any other message, and the same shape on the wire — a client that
        // had to special-case system cards would be a client that renders them slightly wrong.
        Delivery delivery = new Delivery(msgId, conversation.toString(), request.userId(),
                "Nirman", "SYSTEM", request.title() + "\n" + request.body(), null,
                readActions(row.getActions()), sentAt);

        // Straight down a live stream, or a push if nobody is holding one. A card nobody sees is
        // a stalled approval, which is a stalled site.
        boolean live = streams.push(request.userId(),
                sentAt.toEpochMilli() + "-" + msgId, delivery);
        if (!live) {
            String tag = request.tag() != null ? request.tag() : conversation.toString();
            notifier.notify(request.userId(), new Notifier.Notification(
                    request.title(), request.body(),
                    request.deepLink() != null ? request.deepLink() : "/c/" + conversation,
                    tag, -1));
        }
        log.info("Posted a system card to {}", request.userId());
        return msgId;
    }

    /**
     * A path on Nirman's API and never a full URL.
     *
     * <p>The device joins it to the Nirman base it already holds, so a card — however it got
     * here — cannot point a signed-in phone at another host and hand over its token.</p>
     */
    private static void validate(Action action) {
        if (!METHODS.contains(action.method().toUpperCase())) {
            throw new BusinessException("system.method",
                    "That is not a method a card may use.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String path = action.path();
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("..")
                || path.contains("://")) {
            throw new BusinessException("system.path",
                    "A card action must be a path on Nirman's own API.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private Object readActions(String actions) {
        if (actions == null) {
            return null;
        }
        try {
            return json.readTree(actions);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void applyActions(OutboxEntry row, List<Action> actions) {
        if (actions.isEmpty()) {
            return;
        }
        try {
            row.setActions(json.writeValueAsString(actions));
        } catch (JsonProcessingException e) {
            throw new BusinessException("system.actions",
                    "Those actions could not be stored.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
