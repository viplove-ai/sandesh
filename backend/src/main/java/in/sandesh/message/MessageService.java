package in.sandesh.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.sandesh.common.BusinessException;
import in.sandesh.conversation.ConversationId;
import in.sandesh.conversation.ConversationService;
import in.sandesh.directory.NirmanDirectory;
import in.sandesh.media.MediaService;
import in.sandesh.moderation.RestrictionGuard;
import in.sandesh.notify.Notifier;
import in.sandesh.message.MessageDtos.Delivery;
import in.sandesh.message.MessageDtos.MediaRef;
import in.sandesh.message.MessageDtos.SendRequest;
import in.sandesh.message.MessageDtos.SendResponse;
import in.sandesh.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Send, deliver, acknowledge, forget.
 *
 * <p>Fan-out happens on write: the member list is resolved at the instant of sending and one row
 * goes into the outbox per recipient. Somebody posted to the site tomorrow was not on that list
 * and receives nothing, which is how "no history" is enforced — by a join, not by a rule
 * somebody has to remember.</p>
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final Set<String> KINDS = Set.of("TEXT", "IMAGE", "DOC");

    private final OutboxRepository outbox;
    private final MessageIdempotencyRepository ledger;
    private final ConversationService conversations;
    private final NirmanDirectory directory;
    private final StreamRegistry streams;
    private final RestrictionGuard restrictions;
    private final MediaService media;
    private final Notifier notifier;
    private final ObjectMapper json;
    private final int sweepAfterDays;

    public MessageService(OutboxRepository outbox, MessageIdempotencyRepository ledger,
                          ConversationService conversations, NirmanDirectory directory,
                          StreamRegistry streams, RestrictionGuard restrictions,
                          MediaService media, Notifier notifier, ObjectMapper json,
                          @Value("${app.outbox.sweep-after-days:7}") int sweepAfterDays) {
        this.outbox = outbox;
        this.ledger = ledger;
        this.conversations = conversations;
        this.directory = directory;
        this.streams = streams;
        this.restrictions = restrictions;
        this.media = media;
        this.notifier = notifier;
        this.json = json;
        this.sweepAfterDays = sweepAfterDays;
    }

    @Transactional
    public SendResponse send(SendRequest request, AuthenticatedUser sender) {
        restrictions.assertMaySend(sender.userId());
        if (!KINDS.contains(request.kind())) {
            throw new BusinessException("message.kind", "Unknown message kind.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if ("TEXT".equals(request.kind()) && (request.body() == null || request.body().isBlank())) {
            throw new BusinessException("message.empty", "A message needs something in it.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // The re-send of a message that already went out is answered with the original receipt,
        // not with a second message. A phone on a bad link retries; that must be free.
        Optional<MessageIdempotency> seen = ledger.findById(request.clientMsgId());
        if (seen.isPresent()) {
            MessageIdempotency previous = seen.get();
            if (!previous.getSenderId().equals(sender.userId())) {
                throw BusinessException.conflict("message.client-id-taken",
                        "That message id belongs to somebody else.");
            }
            return new SendResponse(request.clientMsgId(), previous.getMsgId(),
                    previous.getSentAt());
        }

        if (!"TEXT".equals(request.kind())) {
            if (request.media() == null) {
                throw new BusinessException("message.media-missing",
                        "That message has no file attached.", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            // The declared type is the sender's claim; this is where it stops being taken on
            // trust. Checked before fan-out, so a refused file never becomes a message.
            media.verifyUploaded(request.media().mediaId(), sender.orgId());
        }

        ConversationId conversation = ConversationId.parse(request.convId());
        Set<UUID> recipients = conversations.recipientsOf(conversation, sender);

        UUID msgId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        String media = writeMedia(request.media());

        ledger.save(new MessageIdempotency(request.clientMsgId(), sender.userId(), msgId,
                conversation.toString(), sentAt));

        List<OutboxEntry> rows = recipients.stream()
                .filter(recipient -> !recipient.equals(sender.userId()))   // the sender has it
                .map(recipient -> new OutboxEntry(recipient, sender.userId(), sender.orgId(),
                        conversation.toString(), msgId, request.clientMsgId(), request.kind(),
                        request.body(), media, sentAt))
                .toList();
        outbox.saveAll(rows);

        deliverNow(rows, sender);
        return new SendResponse(request.clientMsgId(), msgId, sentAt);
    }

    /**
     * Hand each row to a live connection if there is one. The row stays in the outbox either
     * way — it leaves only when the device says it has committed the message, never when this
     * process merely managed to write it to a socket.
     */
    private void deliverNow(List<OutboxEntry> rows, AuthenticatedUser sender) {
        String senderName = directory.lookUp(List.of(sender.userId())).stream()
                .findFirst().map(NirmanDirectory.Person::fullName).orElse(sender.username());
        for (OutboxEntry row : rows) {
            boolean live = streams.push(row.getRecipientId(), eventId(row), toDelivery(row, senderName));
            if (!live) {
                // Nobody is holding a connection for them, so wake the phone. The message is
                // already durable in the outbox — this only decides whether they find out now
                // or the next time they open the app.
                wake(row, senderName);
            }
        }
    }

    /** Everything this device has not acknowledged, oldest first. */
    @Transactional(readOnly = true)
    public List<Delivery> pendingFor(UUID recipientId, Instant afterSentAt, UUID afterMsgId) {
        List<OutboxEntry> rows = outbox.pendingFor(recipientId, afterSentAt, afterMsgId);
        Map<UUID, String> names = directory
                .lookUp(rows.stream().map(OutboxEntry::getSenderId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(NirmanDirectory.Person::userId,
                        NirmanDirectory.Person::fullName));
        List<Delivery> out = new ArrayList<>(rows.size());
        for (OutboxEntry row : rows) {
            out.add(toDelivery(row, names.getOrDefault(row.getSenderId(), "Unknown")));
        }
        return out;
    }

    /**
     * The device has the message and has written it down. Only now is the server's copy dropped.
     *
     * <p>Acking on receipt instead would delete the only copy while the phone was still writing
     * it, and a browser killed in that window loses the message permanently. This way a crash
     * costs a redelivery, which is free — the client dedupes on {@code msgId}.</p>
     */
    @Transactional
    public void ack(UUID recipientId, UUID msgId) {
        outbox.ack(recipientId, msgId);
    }

    public long pendingCount(UUID recipientId) {
        return outbox.countByRecipientId(recipientId);
    }

    /**
     * The retention policy, expressed as a job because the store is durable.
     *
     * <p>Redis would have expired these for free and lost the whole spool on a restart, which
     * for a messenger is a correctness bug rather than a performance characteristic.</p>
     */
    @Scheduled(cron = "${app.outbox.sweep-cron:0 30 3 * * *}")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(sweepAfterDays));
        int spooled = outbox.sweepOlderThan(cutoff);
        int ledgered = ledger.sweepOlderThan(cutoff);
        if (spooled > 0 || ledgered > 0) {
            log.info("Swept {} undelivered messages and {} idempotency rows older than {} days",
                    spooled, ledgered, sweepAfterDays);
        }
    }

    /**
     * The notification carries enough to render itself, deliberately.
     *
     * <p>iOS forbids a push that shows nothing, so the "ping then fetch" shape is not available:
     * whatever arrives here has to be a complete notification on its own. Collapsed by
     * conversation, so twenty messages in a site channel are one line and not twenty.</p>
     */
    private void wake(OutboxEntry row, String senderName) {
        String preview = switch (row.getKind()) {
            case "IMAGE" -> "sent a photograph";
            case "DOC" -> "sent a document";
            default -> row.getBody() == null ? "sent a message" : row.getBody();
        };
        try {
            notifier.notify(row.getRecipientId(), new Notifier.Notification(
                    senderName, preview, "/c/" + row.getConvId(), row.getConvId(),
                    (int) Math.min(Integer.MAX_VALUE, pendingCount(row.getRecipientId()))));
        } catch (RuntimeException e) {
            // A push service having a bad afternoon must never fail a send that already worked.
            log.warn("Could not notify {}", row.getRecipientId(), e);
        }
    }

    static String eventId(OutboxEntry row) {
        // The client sends this back as Last-Event-ID, and the pair is the total order the
        // outbox query resumes from.
        return row.getSentAt().toEpochMilli() + "-" + row.getMsgId();
    }

    private Delivery toDelivery(OutboxEntry row, String senderName) {
        return new Delivery(row.getMsgId(), row.getConvId(), row.getSenderId(), senderName,
                row.getKind(), row.getBody(), readMedia(row.getMedia()), row.getSentAt());
    }

    private String writeMedia(MediaRef media) {
        if (media == null) {
            return null;
        }
        try {
            return json.writeValueAsString(media);
        } catch (JsonProcessingException e) {
            throw new BusinessException("message.media", "That attachment could not be read.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private MediaRef readMedia(String media) {
        if (media == null) {
            return null;
        }
        try {
            return json.readValue(media, MediaRef.class);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable media envelope; delivering the message without it");
            return null;
        }
    }
}
