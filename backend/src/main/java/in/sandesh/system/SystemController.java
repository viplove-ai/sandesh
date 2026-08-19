package in.sandesh.system;

import in.sandesh.common.BusinessException;
import in.sandesh.system.SystemDtos.NotifyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Where Nirman posts a card. Service-to-service, so there is no user to represent and no JWT —
 * a shared token instead.
 *
 * <p>Nirman should call this <b>after commit and asynchronously</b>. A notification that cannot
 * be delivered must never roll back the approval that triggered it, and a synchronous HTTP call
 * inside a transaction makes the messenger's uptime a dependency of Nirman's writes.</p>
 */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Nirman posts actionable cards here")
public class SystemController {

    private final SystemMessageService system;
    private final SystemTokenProperties properties;

    public SystemController(SystemMessageService system, SystemTokenProperties properties) {
        this.system = system;
        this.properties = properties;
    }

    @PostMapping("/notify")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Post a card into somebody's Nirman channel")
    public void notify(@RequestHeader(value = "X-Service-Token", required = false) String token,
                       @Valid @RequestBody NotifyRequest request) {
        requireServiceToken(token);
        system.post(request);
    }

    /**
     * Constant-time comparison, because a naive equals leaks the token one character at a time
     * to anybody willing to measure. Cheap here and the kind of thing that is never retrofitted.
     */
    private void requireServiceToken(String presented) {
        if (!properties.configured() || presented == null) {
            throw BusinessException.forbidden("This endpoint is not available.");
        }
        boolean ok = MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                properties.token().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throw BusinessException.forbidden("This endpoint is not available.");
        }
    }
}
