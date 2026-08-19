package in.sandesh.message;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class MessageDtos {

    /** What the device uploaded, as the sender describes it. */
    public record MediaRef(UUID mediaId, String fileName, String contentType, long sizeBytes,
                           Integer width, Integer height) {
    }

    public record SendRequest(
            @NotNull UUID clientMsgId,
            @NotNull String convId,
            @NotNull String kind,
            @Size(max = 4000) String body,
            MediaRef media) {
    }

    /** The sender's receipt. Not the message — the sender already has that. */
    public record SendResponse(UUID clientMsgId, UUID msgId, Instant sentAt) {
    }

    /** One delivered message, as it goes out over the stream. */
    public record Delivery(UUID msgId, String convId, UUID from, String fromName, String kind,
                           String body, MediaRef media, Instant sentAt) {
    }

    private MessageDtos() {
    }
}
