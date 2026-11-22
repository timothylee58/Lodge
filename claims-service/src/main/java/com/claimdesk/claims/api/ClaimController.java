package com.claimdesk.claims.api;

import com.claimdesk.claims.api.dto.*;
import com.claimdesk.claims.application.ClaimCommandService;
import com.claimdesk.claims.application.ClaimQueryService;
import com.claimdesk.claims.infrastructure.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** Claimant-facing + shared read endpoints. Role gate is in SecurityConfig; ownership is in the services. */
@RestController
@RequestMapping("/api")
public class ClaimController {
    private final ClaimCommandService commands;
    private final ClaimQueryService queries;

    public ClaimController(ClaimCommandService commands, ClaimQueryService queries) {
        this.commands = commands; this.queries = queries;
    }

    @GetMapping("/policies/mine")
    public List<PolicyResponse> myPolicies(@AuthenticationPrincipal AuthenticatedUser me) {
        return queries.myPolicies(me.id());
    }

    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimDetailResponse submit(@AuthenticationPrincipal AuthenticatedUser me,
                                      @Valid @RequestBody SubmitClaimRequest req) {
        var claim = commands.submit(me.id(), req.policyId(), req.incidentDate(), req.description());
        return queries.detail(claim.getId(), me);
    }

    @GetMapping("/claims")
    public List<ClaimSummary> mine(@AuthenticationPrincipal AuthenticatedUser me,
                                   @RequestParam(defaultValue = "true") boolean mine) {
        if (!mine) throw new IllegalArgumentException("Only mine=true is supported for claimants");
        return queries.mine(me.id());
    }

    @GetMapping("/claims/{id}")
    public ClaimDetailResponse detail(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me) {
        return queries.detail(id, me);
    }

    @PostMapping("/claims/{id}/info-responses")
    public ClaimDetailResponse respond(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me,
                                       @Valid @RequestBody InfoResponseRequest req) {
        commands.respondToInfo(id, me.id(), req.response());
        return queries.detail(id, me);
    }
}
