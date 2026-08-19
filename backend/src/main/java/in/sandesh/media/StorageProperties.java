package in.sandesh.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey, String bucket,
                                int signedUrlMinutes, long maxFileSizeBytes,
                                List<String> allowedContentTypes) {
}
