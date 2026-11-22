package unit.tests.servicingagent.controller;

import unit.tests.servicingagent.admin.AdminOverrideService;
import unit.tests.servicingagent.dlt.DltService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Supervisor-only endpoints (FR-8.1 to FR-8.3). Access requires the
 * {@code SCOPE_servicing.supervisor} authority when JWT validation is enabled
 * ({@code OAUTH2_ISSUER_URI} set); in local demo mode without an IdP the guard is open.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin")
@org.springframework.validation.annotation.Validated
@PreAuthorize("!@adminAccess.jwtEnabled() or hasAuthority('SCOPE_servicing.supervisor')")
public class AdminOverrideController {

    static final String DEMO_SUBJECT = "demo-mode-no-idp";

    public record WaiverOverrideRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_\\-]{1,32}$", message = "must be 1-32 chars of letters, digits, _ or -") String customerId,
            @Pattern(regexp = "^[A-Za-z0-9_\\-]{0,32}$", message = "must be at most 32 chars of letters, digits, _ or -") String invoiceId,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(min = 10, max = 1000, message = "justification must be 10-1000 characters") String justification
    ) {}

    public record ReplayRequest(
            @NotBlank String topic,
            @Min(1) @Max(500) Integer maxMessages
    ) {}

    private final AdminOverrideService overrideService;
    private final DltService dltService;

    public AdminOverrideController(AdminOverrideService overrideService, DltService dltService) {
        this.overrideService = overrideService;
        this.dltService = dltService;
    }

    @PostMapping("/overrides/waiver")
    @Operation(summary = "Apply a supervisor waiver override",
            description = "Waives the invoice (optional), increments the customer's waiver count and "
                    + "writes an append-only audit entry with the supervisor's JWT subject, justification and timestamp.")
    public ResponseEntity<AdminOverrideService.OverrideResult> waiverOverride(
            @RequestBody @Valid WaiverOverrideRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String sub = jwt != null ? jwt.getSubject() : DEMO_SUBJECT;
        return ResponseEntity.ok(overrideService.applyWaiverOverride(
                sub, request.customerId(), request.invoiceId(), request.amount(), request.justification()));
    }

    @GetMapping("/kafka/dlt")
    @Operation(summary = "Inspect dead-letter topics",
            description = "Returns unreplayed depth per DLT and a preview of pending records with failure headers.")
    public ResponseEntity<Map<String, Object>> inspectDlt(
            @RequestParam(defaultValue = "5") @Min(0) @Max(50) int preview) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String topic : dltService.dltTopics()) {
            List<DltService.DltMessage> pending = preview > 0 ? dltService.peek(topic, preview) : List.of();
            out.put(topic, Map.of("depth", dltService.depth(topic), "pending", pending));
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/kafka/dlt/replay")
    @Operation(summary = "Replay dead-letter records",
            description = "Republishes pending records from a *.DLT topic back to its original topic. "
                    + "Fix the root cause first; replayed records are deduplicated and LSN-checked by the consumer.")
    public ResponseEntity<DltService.ReplayResult> replay(@RequestBody @Valid ReplayRequest request) {
        int max = request.maxMessages() == null ? 100 : request.maxMessages();
        return ResponseEntity.ok(dltService.replay(request.topic(), max));
    }
}
