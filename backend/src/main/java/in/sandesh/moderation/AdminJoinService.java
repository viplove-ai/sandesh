package in.sandesh.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.sandesh.common.BusinessException;
import in.sandesh.conversation.ConversationId;
import in.sandesh.conversation.ConversationService;
import in.sandesh.directory.NirmanDirectory;
import in.sandesh.message.MessageDtos.SendRequest;
import in.sandesh.message.MessageService;
import in.sandesh.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How an administrator gets into a site conversation.
 *
 * <p>Before this, the only way was to be named on the site in Nirman as its ENGINEER or
 * SUPERVISOR — which is to say, falsifying an operational register in order to send a message.
 * An admin already holds the {@code ALL} sites claim and can read every figure the site
 * produces, so letting them in is not privilege escalation. What is being designed here is not
 * authorisation. It is transparency:</p>
 *
 * <blockquote>An administrator may go anywhere, and never silently.</blockquote>
 *
 * <p>So joining posts a line in the channel, shows them in the member list, and writes an audit
 * row. There is no path anywhere in this service by which an admin reads a channel without the
 * channel knowing.</p>
 */
@Service
public class AdminJoinService {

    private final MessageService messages;
    private final ConversationService conversations;
    private final NirmanDirectory directory;
    private final ChatAuditRepository audit;
    private final ObjectMapper json;

    public AdminJoinService(MessageService messages, ConversationService conversations,
                            NirmanDirectory directory, ChatAuditRepository audit,
                            ObjectMapper json) {
        this.messages = messages;
        this.conversations = conversations;
        this.directory = directory;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public void join(String rawConvId, AuthenticatedUser admin) {
        ConversationId conversation = requireJoinable(rawConvId, admin);

        String name = directory.lookUp(List.of(admin.userId())).stream()
                .findFirst().map(NirmanDirectory.Person::fullName).orElse(admin.username());

        // Announced with an ordinary message rather than a side channel, so it appears in the
        // thread everybody is already reading and cannot be styled away later.
        messages.send(new SendRequest(UUID.randomUUID(), conversation.toString(), "TEXT",
                name + " (Administrator) joined this conversation.", null), admin);

        record(admin, "CHANNEL_JOIN", conversation.toString());
    }

    @Transactional
    public void leave(String rawConvId, AuthenticatedUser admin) {
        ConversationId conversation = requireJoinable(rawConvId, admin);

        String name = directory.lookUp(List.of(admin.userId())).stream()
                .findFirst().map(NirmanDirectory.Person::fullName).orElse(admin.username());

        messages.send(new SendRequest(UUID.randomUUID(), conversation.toString(), "TEXT",
                name + " (Administrator) left this conversation.", null), admin);

        record(admin, "CHANNEL_LEAVE", conversation.toString());
    }

    /**
     * Sites and projects only.
     *
     * <p>A direct conversation is refused outright and that is the whole point of the limit: an
     * administrator may enter a work channel where the work is, and may not appear inside two
     * people talking. Being able to go anywhere visibly is a different thing from being able to
     * go everywhere.</p>
     */
    private ConversationId requireJoinable(String rawConvId, AuthenticatedUser admin) {
        if (!admin.allSites() && !admin.isAdmin()) {
            throw BusinessException.forbidden("Only an administrator can do that.");
        }
        ConversationId conversation = ConversationId.parse(rawConvId);
        if (conversation.kind() != ConversationId.Kind.SITE
                && conversation.kind() != ConversationId.Kind.PROJECT) {
            throw BusinessException.forbidden(
                    "An administrator can only join a site or project conversation.");
        }
        // Confirms the site exists in this org and is live, and takes the member list warm.
        conversations.members(conversation);
        return conversation;
    }

    private void record(AuthenticatedUser admin, String action, String convId) {
        try {
            audit.save(new ChatAudit(admin.orgId(), admin.userId(), action, null,
                    json.writeValueAsString(Map.of("convId", convId,
                            "at", Instant.now().toString()))));
        } catch (Exception e) {
            audit.save(new ChatAudit(admin.orgId(), admin.userId(), action, null, null));
        }
    }
}
