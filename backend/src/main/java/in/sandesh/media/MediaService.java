package in.sandesh.media;

import in.sandesh.common.BusinessException;
import in.sandesh.media.MediaDtos.DownloadUrlResponse;
import in.sandesh.media.MediaDtos.UploadUrlRequest;
import in.sandesh.media.MediaDtos.UploadUrlResponse;
import in.sandesh.security.AuthenticatedUser;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Bytes never pass through this service. The device is handed a presigned URL and uploads
 * straight to storage, which is what keeps a 700 KB photograph off the API's heap and out of its
 * request timeout on a 2G connection.
 */
@Service
public class MediaService {

    private final MinioClient minio;
    private final MediaObjectRepository objects;
    private final StorageProperties properties;

    public MediaService(MinioClient minio, MediaObjectRepository objects,
                        StorageProperties properties) {
        this.minio = minio;
        this.objects = objects;
        this.properties = properties;
    }

    @Transactional
    public UploadUrlResponse requestUpload(UploadUrlRequest request, AuthenticatedUser uploader) {
        if (request.sizeBytes() > properties.maxFileSizeBytes()) {
            throw new BusinessException("media.too-large",
                    "That file is larger than the limit.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!properties.allowedContentTypes().contains(request.contentType())) {
            throw new BusinessException("media.type",
                    "That kind of file cannot be sent yet.", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        UUID mediaId = UUID.randomUUID();
        // Partitioned by org so a bucket policy or a lifecycle rule can be written per tenant
        // later without moving anything.
        String objectKey = uploader.orgId() + "/" + mediaId;

        objects.save(new MediaObject(mediaId, uploader.userId(), uploader.orgId(), request.convId(),
                objectKey, request.contentType(), request.sizeBytes(), request.fileName()));

        return new UploadUrlResponse(mediaId,
                presign(Method.PUT, objectKey, null), properties.signedUrlMinutes());
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse requestDownload(UUID mediaId, AuthenticatedUser caller) {
        MediaObject object = objects.findById(mediaId)
                .orElseThrow(() -> BusinessException.notFound("That file"));
        if (!object.getOrgId().equals(caller.orgId())) {
            throw BusinessException.notFound("That file");
        }
        // Served as an attachment with nosniff: a site engineer will be sent a ".pdf" that is an
        // HTML file eventually, and it must not render in the browser when he taps it.
        String disposition = "attachment; filename=\""
                + object.getFileName().replace("\"", "") + "\"";
        return new DownloadUrlResponse(presign(Method.GET, object.getObjectKey(), disposition),
                object.getFileName(), object.getContentType(), properties.signedUrlMinutes());
    }

    private String presign(Method method, String objectKey, String disposition) {
        try {
            var builder = GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry(properties.signedUrlMinutes(), TimeUnit.MINUTES);
            if (disposition != null) {
                builder.extraQueryParams(Map.of("response-content-disposition", disposition));
            }
            return minio.getPresignedObjectUrl(builder.build());
        } catch (Exception e) {
            throw new BusinessException("media.storage",
                    "Storage is not answering. Try again.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
