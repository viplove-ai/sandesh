package in.sandesh.moderation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.sandesh.common.BusinessException;
import in.sandesh.moderation.ChatRestriction.Level;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether this person may connect, and whether they may speak.
 *
 * <p>Cached for the same 120 seconds as the site membership, and for the same reason: a
 * restriction applied now closes a live stream within two minutes, which is the window written
 * into the plan rather than a number that fell out of an implementation. The cache is dropped
 * explicitly when an administrator applies or lifts one, so the person acting sees it take
 * effect immediately even though everybody else is inside the window.</p>
 */
@Component
public class RestrictionGuard {

    private static final Duration WINDOW = Duration.ofSeconds(120);

    private final ChatRestrictionRepository restrictions;
    private final Cache<UUID, Optional<ChatRestriction>> cache =
            Caffeine.newBuilder().expireAfterWrite(WINDOW).maximumSize(10_000).build();

    public RestrictionGuard(ChatRestrictionRepository restrictions) {
        this.restrictions = restrictions;
    }

    public Optional<ChatRestriction> current(UUID userId) {
        return cache.get(userId, id -> restrictions.findById(id)
                .filter(r -> r.isActiveAt(Instant.now())));
    }

    /** Applied when a stream is opened. A blocked device does not get a connection at all. */
    public void assertMayConnect(UUID userId) {
        current(userId)
                .filter(r -> r.getLevel() == Level.BLOCKED)
                .ifPresent(r -> {
                    throw new BusinessException("chat.blocked", r.getReason(), HttpStatus.FORBIDDEN);
                });
    }

    /**
     * Applied when a message is sent. Both levels stop it — a blocked user should never reach
     * here, but a send is the thing worth being certain about.
     */
    public void assertMaySend(UUID userId) {
        current(userId).ifPresent(r -> {
            throw new BusinessException("chat.restricted", r.getReason(), HttpStatus.FORBIDDEN);
        });
    }

    public void forget(UUID userId) {
        cache.invalidate(userId);
    }
}
