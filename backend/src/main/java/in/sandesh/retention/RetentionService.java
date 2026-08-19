package in.sandesh.retention;

import in.sandesh.common.BusinessException;
import in.sandesh.conversation.ConversationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Keeping the work channels, and refusing to keep anything else.
 *
 * <p>The whole of §15 turns on one distinction: <b>retained is not browsable.</b> This class
 * writes the record and reads it back for a device that has lost its copy. It has no method that
 * hands a conversation to an administrator — that is the export gate, which is a different class
 * with two approvers, a reason, and a notice posted into the channel it came from.</p>
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final RetainedMessageRepository retained;
    private final RetentionProperties properties;

    public RetentionService(RetainedMessageRepository retained, RetentionProperties properties) {
        this.retained = retained;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    /**
     * True only for a conversation whose messages may be kept.
     *
     * <p>Direct messages are Tier 3 and no configuration makes them Tier 2. This returns false
     * for them regardless of the flag, which is the first of the three places that rule is
     * enforced — the service refuses it, and a check constraint refuses it again if the service
     * is ever wrong.</p>
     */
    public boolean isRetainable(ConversationId conversation) {
        return properties.enabled() && conversation.kind() != ConversationId.Kind.DIRECT;
    }

    /** Called on the send path, after fan-out. Silent and cheap when retention is off. */
    @Transactional
    public void record(UUID msgId, UUID orgId, ConversationId conversation, UUID senderId,
                       String kind, String body, String media, Instant sentAt) {
        if (!isRetainable(conversation)) {
            return;
        }
        // Written at insert rather than computed at read, so changing the window later does not
        // silently un-delete last year's messages or bring this year's deletion forward.
        Instant retainUntil = sentAt.plus(properties.window());
        retained.save(new RetainedMessage(msgId, orgId, conversation.toString(), senderId, kind,
                body, media, sentAt, retainUntil));
    }

    /**
     * What a new device gets back for one channel.
     *
     * <p>The caller supplies the window, and the caller derives it from the person's own
     * assignment dates — so a re-issued handset returns the period they were actually posted
     * there and nothing outside it. That bound is the difference between restoring somebody's
     * work and handing them a site's history they were never part of.</p>
     */
    @Transactional(readOnly = true)
    public List<RetainedMessage> resync(ConversationId conversation, Instant from, Instant to) {
        if (conversation.kind() == ConversationId.Kind.DIRECT) {
            throw new BusinessException("retention.direct",
                    "Direct messages are not kept anywhere but on the two devices.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!properties.enabled()) {
            return List.of();
        }
        return retained.window(conversation.toString(), from, to);
    }

    /**
     * The window, enforced.
     *
     * <p>A retention period that nothing deletes against is "permanent" wearing a policy's
     * clothes — and a policy the company cannot demonstrate it follows is worse than no policy,
     * because it was written down.</p>
     */
    @Scheduled(cron = "${app.retention.sweep-cron:0 0 4 * * *}")
    @Transactional
    public void deleteExpired() {
        if (!properties.enabled()) {
            return;
        }
        int deleted = retained.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Retention window reached: deleted {} messages", deleted);
        }
    }
}
