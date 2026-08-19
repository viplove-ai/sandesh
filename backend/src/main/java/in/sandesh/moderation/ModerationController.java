package in.sandesh.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.sandesh.common.BusinessException;
import in.sandesh.directory.NirmanDirectory;
import in.sandesh.message.StreamRegistry;
import in.sandesh.moderation.ModerationDtos.AuditView;
import in.sandesh.moderation.ModerationDtos.ReportRequest;
import in.sandesh.moderation.ModerationDtos.RestrictRequest;
import in.sandesh.moderation.ModerationDtos.RestrictionView;
import in.sandesh.security.AuthenticatedUser;
import in.sandesh.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Restricting somebody, and the report button that is how an administrator finds out they
 * should.
 *
 * <p>Guarded by {@code chat:restrict}, a permission seeded by a Nirman migration rather than a
 * constant here — a new permission is a migration, which is Nirman's convention and worth
 * keeping across the boundary.</p>
 */
@RestController
@RequestMapping("/api/v1/moderation")
@Tag(name = "Moderation", description = "Mute, block, report — and the audit trail of all three")
public class ModerationController {

    private static final String RESTRICT = "chat:restrict";

    private final AdminJoinService adminJoin;
    private final ChatRestrictionRepository restrictions;
    private final ChatReportRepository reports;
    private final ChatAuditRepository audit;
    private final RestrictionGuard guard;
    private final NirmanDirectory directory;
    private final StreamRegistry streams;
    private final CurrentUser currentUser;
    private final ObjectMapper json;

    public ModerationController(AdminJoinService adminJoin,
                                ChatRestrictionRepository restrictions,
                                ChatReportRepository reports, ChatAuditRepository audit,
                                RestrictionGuard guard, NirmanDirectory directory,
                                StreamRegistry streams, CurrentUser currentUser,
                                ObjectMapper json) {
        this.adminJoin = adminJoin;
        this.restrictions = restrictions;
        this.reports = reports;
        this.audit = audit;
        this.guard = guard;
        this.directory = directory;
        this.streams = streams;
        this.currentUser = currentUser;
        this.json = json;
    }

    @PostMapping("/restrictions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Mute or block a user in the messenger")
    public void restrict(@Valid @RequestBody RestrictRequest request) {
        AuthenticatedUser admin = requireRestrictPermission();

        boolean sameOrg = directory.lookUp(List.of(request.userId())).stream()
                .anyMatch(p -> p.orgId().equals(admin.orgId()));
        if (!sameOrg) {
            throw BusinessException.notFound("That user");
        }
        if (request.userId().equals(admin.userId())) {
            throw new BusinessException("moderation.self",
                    "You cannot restrict yourself.", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        restrictions.save(new ChatRestriction(request.userId(), admin.orgId(), request.level(),
                request.reason(), admin.userId(), request.until()));
        guard.forget(request.userId());

        // A block that leaves the stream open is not a block. Closing it here is what makes it
        // take effect now rather than inside the 120-second window everybody else is subject to.
        if (request.level() == ChatRestriction.Level.BLOCKED) {
            streams.disconnect(request.userId());
        }

        record(admin, "RESTRICT", request.userId(),
                Map.of("level", request.level().name(), "reason", request.reason()));
    }

    @DeleteMapping("/restrictions/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Lift a restriction")
    public void lift(@PathVariable UUID userId) {
        AuthenticatedUser admin = requireRestrictPermission();
        restrictions.deleteById(userId);
        guard.forget(userId);
        record(admin, "UNRESTRICT", userId, Map.of());
    }

    @GetMapping("/restrictions")
    @Transactional(readOnly = true)
    @Operation(summary = "Who is currently restricted")
    public List<RestrictionView> list() {
        AuthenticatedUser admin = requireRestrictPermission();
        List<ChatRestriction> rows = restrictions.findByOrgId(admin.orgId());
        Map<UUID, String> names = directory
                .lookUp(rows.stream().map(ChatRestriction::getUserId).toList()).stream()
                .collect(Collectors.toMap(NirmanDirectory.Person::userId,
                        NirmanDirectory.Person::fullName));
        return rows.stream()
                .map(r -> new RestrictionView(r.getUserId(),
                        names.getOrDefault(r.getUserId(), "Unknown"), r.getLevel().name(),
                        r.getReason(), r.getUntil(), r.getUntil()))
                .toList();
    }

    /**
     * The input side of blocking. Any member may use it — without one, restricting somebody is a
     * control an administrator only reaches for after being telephoned about it.
     */
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Report a message to the administrators")
    public void report(@Valid @RequestBody ReportRequest request) {
        AuthenticatedUser reporter = currentUser.required();
        reports.save(new ChatReport(reporter.orgId(), reporter.userId(), request.subjectId(),
                request.convId(), request.quotedBody(), request.note()));
    }

    /**
     * An administrator entering a site conversation they hold no assignment to.
     *
     * <p>Announced in the channel, shown in the member list, and audited. There is no quiet
     * variant of this endpoint and there must not be one.</p>
     */
    @PostMapping("/channels/{convId}/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Join a site or project conversation as an administrator, visibly")
    public void joinChannel(@PathVariable String convId) {
        adminJoin.join(convId, currentUser.required());
    }

    @PostMapping("/channels/{convId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Leave it again, also visibly")
    public void leaveChannel(@PathVariable String convId) {
        adminJoin.leave(convId, currentUser.required());
    }

    @GetMapping("/audit")
    @Transactional(readOnly = true)
    @Operation(summary = "What administrators have done here, visible to all of them")
    public List<AuditView> auditTrail() {
        AuthenticatedUser admin = requireRestrictPermission();
        List<ChatAudit> rows = audit
                .findByOrgIdOrderByAtDesc(admin.orgId(), PageRequest.of(0, 100)).getContent();
        Map<UUID, String> names = directory
                .lookUp(rows.stream().map(ChatAudit::getActorId).distinct().toList()).stream()
                .collect(Collectors.toMap(NirmanDirectory.Person::userId,
                        NirmanDirectory.Person::fullName));
        return rows.stream()
                .map(r -> new AuditView(r.getActorId(),
                        names.getOrDefault(r.getActorId(), "Unknown"), r.getAction(),
                        r.getSubjectId(), r.getAt()))
                .toList();
    }

    private AuthenticatedUser requireRestrictPermission() {
        AuthenticatedUser user = currentUser.required();
        if (!user.hasPermission(RESTRICT) && !user.isAdmin()) {
            throw BusinessException.forbidden("You cannot restrict users.");
        }
        return user;
    }

    private void record(AuthenticatedUser actor, String action, UUID subject,
                        Map<String, String> detail) {
        try {
            audit.save(new ChatAudit(actor.orgId(), actor.userId(), action, subject,
                    json.writeValueAsString(detail)));
        } catch (JsonProcessingException e) {
            audit.save(new ChatAudit(actor.orgId(), actor.userId(), action, subject, null));
        }
    }
}
