package in.sandesh.conversation;

import java.util.List;
import java.util.UUID;

public final class ConversationDtos {

    public record MemberView(UUID userId, String fullName, String username) {
    }

    public record ConversationView(String id, String kind, String name, String subtitle,
                                   List<MemberView> members) {
    }

    public record PersonView(UUID userId, String fullName, String username) {
    }

    private ConversationDtos() {
    }
}
