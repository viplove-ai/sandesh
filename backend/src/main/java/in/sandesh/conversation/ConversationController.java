package in.sandesh.conversation;

import in.sandesh.conversation.ConversationDtos.ConversationView;
import in.sandesh.conversation.ConversationDtos.PersonView;
import in.sandesh.directory.NirmanDirectory;
import in.sandesh.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Conversations", description = "Derived from Nirman's site assignments; never stored here")
public class ConversationController {

    private final ConversationService conversations;
    private final NirmanDirectory directory;
    private final CurrentUser currentUser;

    public ConversationController(ConversationService conversations, NirmanDirectory directory,
                                  CurrentUser currentUser) {
        this.conversations = conversations;
        this.directory = directory;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "The caller's site and project conversations")
    public List<ConversationView> list() {
        return conversations.listFor(currentUser.required());
    }

    @GetMapping("/directory")
    @Operation(summary = "Org-wide people search, for a 1:1 with somebody outside your sites")
    public List<PersonView> directory(@RequestParam("q") String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }
        return directory.search(currentUser.orgId(), query.trim(), 25).stream()
                .map(p -> new PersonView(p.userId(), p.fullName(), p.username()))
                .toList();
    }
}
