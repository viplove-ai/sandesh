package in.sandesh.media;

import in.sandesh.media.MediaDtos.DownloadUrlResponse;
import in.sandesh.media.MediaDtos.UploadUrlRequest;
import in.sandesh.media.MediaDtos.UploadUrlResponse;
import in.sandesh.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "Media", description = "Presigned upload and download; bytes never pass through here")
public class MediaController {

    private final MediaService media;
    private final CurrentUser currentUser;

    public MediaController(MediaService media, CurrentUser currentUser) {
        this.media = media;
        this.currentUser = currentUser;
    }

    @PostMapping("/upload-url")
    @Operation(summary = "A presigned PUT the device uploads to directly")
    public UploadUrlResponse uploadUrl(@Valid @RequestBody UploadUrlRequest request) {
        return media.requestUpload(request, currentUser.required());
    }

    @GetMapping("/{mediaId}/download-url")
    @Operation(summary = "A short-lived presigned GET for a file in the caller's organisation")
    public DownloadUrlResponse downloadUrl(@PathVariable UUID mediaId) {
        return media.requestDownload(mediaId, currentUser.required());
    }
}
