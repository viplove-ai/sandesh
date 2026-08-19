package in.sandesh.conversation;

import in.sandesh.common.BusinessException;
import in.sandesh.conversation.ConversationDtos.ConversationView;
import in.sandesh.conversation.ConversationDtos.MemberView;
import in.sandesh.directory.NirmanDirectory;
import in.sandesh.security.AuthenticatedUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Membership, derived on every call from Nirman's views.
 *
 * <p>Naming a supervisor on a site in Nirman opens the assignment row that puts him in that
 * site's conversation; closing it takes him out. Nobody administers a chat group, ever.</p>
 */
@Service
public class ConversationService {

    /** Seeded by a Nirman migration, following its convention that a permission is a migration. */
    private static final String ANNOUNCE = "chat:announce";

    private final NirmanDirectory directory;

    public ConversationService(NirmanDirectory directory) {
        this.directory = directory;
    }

    /**
     * What this person opens the app to. Sites first, then the projects those sites belong to.
     *
     * <p>Empty for an accountant, an admin or a new hire — which is correct and is why the client
     * shows the directory rather than a blank list. Being unassigned is a normal state here, not
     * an anomaly.</p>
     */
    public List<ConversationView> listFor(AuthenticatedUser user) {
        List<NirmanDirectory.Membership> memberships = directory.membershipsOf(user.userId());
        List<ConversationView> out = new ArrayList<>();

        for (NirmanDirectory.Membership m : memberships) {
            out.add(new ConversationView(
                    ConversationId.site(m.siteId()).toString(), "SITE",
                    m.siteName(), m.projectName(), members(ConversationId.site(m.siteId()))));
        }

        // One entry per distinct project, ordered as the memberships came back.
        Map<UUID, String> projects = new LinkedHashMap<>();
        memberships.forEach(m -> projects.putIfAbsent(m.projectId(), m.projectName()));
        projects.forEach((projectId, name) -> out.add(new ConversationView(
                ConversationId.project(projectId).toString(), "PROJECT",
                name, "Everyone on this project", List.of())));

        // Nirman's own channel, above everything: it is the one that carries things waiting on
        // this person rather than things being said to them.
        out.add(0, new ConversationView(
                ConversationId.system(user.userId()).toString(), "SYSTEM",
                "Nirman", "Approvals and verifications waiting on you", List.of()));

        // Everybody gets this one, posting or not. It is the answer to an app that opens empty
        // for an accountant, an administrator or a new hire — and to "send something to the
        // whole team", which was the other thing asked of this product.
        out.add(0, new ConversationView(
                ConversationId.org(user.orgId()).toString(), "ORG",
                "Announcements", "Everyone at your company", List.of()));

        return out;
    }

    /**
     * Everyone who receives a message sent here, resolved now rather than stored.
     *
     * <p>The direct case is the amended rule: any two people in the same organisation, with no
     * shared-site test. Requiring a shared site left an accountant — whose whole job is chasing a
     * bill — able to message nobody at all.</p>
     */
    public Set<UUID> recipientsOf(ConversationId conversation, AuthenticatedUser sender) {
        return switch (conversation.kind()) {
            case SITE -> {
                Set<UUID> members = directory.membersOfSite(conversation.a()).stream()
                        .map(NirmanDirectory.Person::userId).collect(Collectors.toSet());
                requireMember(members, sender, "That site is not assigned to you.");
                yield members;
            }
            case PROJECT -> {
                Set<UUID> members = directory.membershipsOf(sender.userId()).stream()
                        .filter(m -> m.projectId().equals(conversation.a()))
                        .flatMap(m -> directory.membersOfSite(m.siteId()).stream())
                        .map(NirmanDirectory.Person::userId)
                        .collect(Collectors.toSet());
                requireMember(members, sender, "That project is not assigned to you.");
                yield members;
            }
            case ORG -> {
                if (!conversation.a().equals(sender.orgId())) {
                    throw BusinessException.forbidden("That is not your organisation.");
                }
                // Everyone reads; only chat:announce writes. This is the one channel where
                // membership and the right to post are different questions.
                if (!sender.hasPermission(ANNOUNCE) && !sender.isAdmin()) {
                    throw BusinessException.forbidden(
                            "Only an administrator can post an announcement.");
                }
                yield directory.membersOfOrg(sender.orgId()).stream()
                        .map(NirmanDirectory.Person::userId).collect(Collectors.toSet());
            }
            case SYSTEM -> {
                // Nobody sends to this channel. Nirman posts through the service endpoint, which
                // does not come through here, and a person replying to it would be talking to a
                // record rather than to anybody.
                throw BusinessException.forbidden("You cannot post to the Nirman channel.");
            }
            case DIRECT -> {
                if (!conversation.a().equals(sender.userId())
                        && !conversation.b().equals(sender.userId())) {
                    throw BusinessException.forbidden("That conversation is not yours.");
                }
                UUID other = conversation.otherParty(sender.userId());
                boolean sameOrg = directory.lookUp(List.of(other)).stream()
                        .anyMatch(p -> p.orgId().equals(sender.orgId()));
                if (!sameOrg) {
                    throw BusinessException.forbidden("That person is not in your organisation.");
                }
                yield Set.of(conversation.a(), conversation.b());
            }
        };
    }

    public List<MemberView> members(ConversationId conversation) {
        if (conversation.kind() != ConversationId.Kind.SITE) {
            return List.of();
        }
        return directory.membersOfSite(conversation.a()).stream()
                .map(p -> new MemberView(p.userId(), p.fullName(), p.username()))
                .toList();
    }

    /**
     * An admin holds the ALL claim and usually no assignment rows at all, so the membership test
     * would shut them out of every site. They are let in — and the plan's rule is that they may
     * go anywhere and never silently, so the join is announced in the channel by the caller.
     */
    private void requireMember(Set<UUID> members, AuthenticatedUser user, String refusal) {
        if (!members.contains(user.userId()) && !user.allSites()) {
            throw BusinessException.forbidden(refusal);
        }
    }
}
