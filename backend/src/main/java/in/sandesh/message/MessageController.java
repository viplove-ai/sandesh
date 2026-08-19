package in.sandesh.message;

import in.sandesh.message.MessageDtos.SendRequest;
import in.sandesh.message.MessageDtos.SendResponse;
import in.sandesh.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Sending is an ordinary POST rather than a socket frame, which buys the idempotency key, the
 * RFC 7807 error body and the retry semantics Nirman already speaks — see docs/PLAN.md §21.
 */
@RestController
@RequestMapping("/api/v1/messages")
@Tag(name = "Messages", description = "Send, and acknowledge receipt so the server can forget")
public class MessageController {

    private final MessageService messages;
    private final CurrentUser currentUser;

    public MessageController(MessageService messages, CurrentUser currentUser) {
        this.messages = messages;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Send a message; re-sending the same clientMsgId returns the first receipt")
    public SendResponse send(@Valid @RequestBody SendRequest request) {
        return messages.send(request, currentUser.required());
    }

    @PostMapping("/{msgId}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "The device has committed this message; drop the server's copy")
    public void ack(@PathVariable UUID msgId) {
        messages.ack(currentUser.id(), msgId);
    }
}
