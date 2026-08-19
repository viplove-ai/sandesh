package in.sandesh.retention;

import in.sandesh.common.BusinessException;
import in.sandesh.conversation.ConversationId;
import in.sandesh.directory.NirmanDirectory;
import in.sandesh.security.AuthenticatedUser;
import in.sandesh.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Getting a conversation back onto a device that lost it.
 *
 * <p>Note what is not here: any way for an administrator to read a channel they are not in.
 * A caller can only re-sync a conversation they are currently a member of, and only for the
 * period they were assigned to it. Reading somebody else's channel is the export gate, which is
 * two people and a written reason.</p>
 */
@RestController
@RequestMapping("/api/v1/retention")
@Tag(name = "Retention", description = "Re-sync a work channel onto a new device")
public class RetentionController {

    public record RetainedView(UUID msgId, String convId, UUID from, String fromName, String kind,
                               String body, Instant sentAt) {
    }

    private final RetentionService retention;
    private final NirmanDirectory directory;
    private final CurrentUser currentUser;

    public RetentionController(RetentionService retention, NirmanDirectory directory,
                               CurrentUser currentUser) {
        this.retention = retention;
        this.directory = directory;
        this.currentUser = currentUser;
    }

    @GetMapping("/{convId}")
    @Operation(summary = "Replay a work channel for the period the caller was posted to it")
    public List<RetainedView> resync(@org.springframework.web.bind.annotation.PathVariable String convId,
                                     @RequestParam(required = false) Instant since) {
        AuthenticatedUser user = currentUser.required();
        ConversationId conversation = ConversationId.parse(convId);

        // Membership now, not membership then. Somebody who has left the site does not get its
        // history back on a new phone — the copy they already hold is theirs, the channel is not.
        boolean member = switch (conversation.kind()) {
            case SITE -> directory.membershipsOf(user.userId()).stream()
                    .anyMatch(m -> m.siteId().equals(conversation.a()));
            case PROJECT -> directory.membershipsOf(user.userId()).stream()
                    .anyMatch(m -> m.projectId().equals(conversation.a()));
            case ORG -> conversation.a().equals(user.orgId());
            case DIRECT -> false;
        };
        if (!member) {
            throw BusinessException.forbidden("That conversation is not yours to replay.");
        }

        Instant from = since != null ? since : Instant.EPOCH;
        List<RetainedMessage> rows = retention.resync(conversation, from, Instant.now());

        var names = directory.lookUp(rows.stream().map(RetainedMessage::getSenderId).distinct().toList());
        return rows.stream()
                .map(r -> new RetainedView(r.getMsgId(), r.getConvId(), r.getSenderId(),
                        names.stream().filter(p -> p.userId().equals(r.getSenderId()))
                                .findFirst().map(NirmanDirectory.Person::fullName).orElse("Unknown"),
                        r.getKind(), r.getBody(), r.getSentAt()))
                .toList();
    }

    @GetMapping("/status")
    @Operation(summary = "Whether work channels are being kept, and for how long")
    public RetentionStatus status() {
        return new RetentionStatus(retention.isEnabled());
    }

    public record RetentionStatus(boolean enabled) {
    }
}
