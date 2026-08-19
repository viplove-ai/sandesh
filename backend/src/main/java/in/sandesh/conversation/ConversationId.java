package in.sandesh.conversation;

import in.sandesh.common.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * A conversation is named, not stored. {@code site:<uuid>}, {@code proj:<uuid>} or
 * {@code dm:<a>:<b>} with the two ids sorted, so the same pair always names the same
 * conversation whichever of them opens it.
 */
public record ConversationId(Kind kind, UUID a, UUID b) {

    public enum Kind { SITE, PROJECT, DIRECT }

    public static ConversationId site(UUID siteId) {
        return new ConversationId(Kind.SITE, siteId, null);
    }

    public static ConversationId project(UUID projectId) {
        return new ConversationId(Kind.PROJECT, projectId, null);
    }

    /** Sorted, because {@code dm:x:y} and {@code dm:y:x} must not be two conversations. */
    public static ConversationId direct(UUID one, UUID other) {
        return one.compareTo(other) <= 0
                ? new ConversationId(Kind.DIRECT, one, other)
                : new ConversationId(Kind.DIRECT, other, one);
    }

    public static ConversationId parse(String raw) {
        try {
            String[] parts = raw.split(":");
            return switch (parts[0]) {
                case "site" -> site(UUID.fromString(parts[1]));
                case "proj" -> project(UUID.fromString(parts[1]));
                case "dm" -> direct(UUID.fromString(parts[1]), UUID.fromString(parts[2]));
                default -> throw new IllegalArgumentException(parts[0]);
            };
        } catch (RuntimeException malformed) {
            throw new BusinessException("conversation.malformed",
                    "That is not a conversation id.", HttpStatus.BAD_REQUEST);
        }
    }

    /** The other party, given one of them. Direct conversations only. */
    public UUID otherParty(UUID self) {
        if (kind != Kind.DIRECT) {
            throw new IllegalStateException("not a direct conversation");
        }
        return a.equals(self) ? b : a;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case SITE -> "site:" + a;
            case PROJECT -> "proj:" + a;
            case DIRECT -> "dm:" + a + ":" + b;
        };
    }
}
