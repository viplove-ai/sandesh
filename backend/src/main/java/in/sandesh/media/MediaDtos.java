package in.sandesh.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public final class MediaDtos {

    public record UploadUrlRequest(@NotBlank String fileName, @NotBlank String contentType,
                                   @Positive long sizeBytes, String convId) {
    }

    /** The device PUTs the bytes straight to storage; they never pass through this service. */
    public record UploadUrlResponse(UUID mediaId, String uploadUrl, int expiresInMinutes) {
    }

    public record DownloadUrlResponse(String downloadUrl, String fileName, String contentType,
                                      int expiresInMinutes) {
    }

    private MediaDtos() {
    }
}
