package com.lodge.claims.api;

import com.lodge.claims.api.dto.*;
import com.lodge.claims.application.ClaimCommandService;
import com.lodge.claims.application.ClaimQueryService;
import com.lodge.claims.domain.ClaimStatus;
import com.lodge.claims.infrastructure.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Read: OFFICER + MANAGER. Commands: OFFICER only (SecurityConfig); "assigned to me" checked in the service. */
@RestController
@RequestMapping("/api/staff/claims")
public class StaffClaimController {
    private final ClaimCommandService commands;
    private final ClaimQueryService queries;

    public StaffClaimController(ClaimCommandService commands, ClaimQueryService queries) {
        this.commands = commands; this.queries = queries;
    }

    /** assignee = me | unassigned | <uuid>; sort = oldest (default) | waiting (longest in current status) */
    @GetMapping
    public PageResponse<ClaimSummary> queue(@RequestParam(required = false) ClaimStatus status,
                                            @RequestParam(required = false) String assignee,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(defaultValue = "oldest") String sort,
                                            @AuthenticationPrincipal AuthenticatedUser me) {
        return queries.queue(status, assignee, me.id(), page, size, sort);
    }

    /** Overdue / due-soon / on-track counts for "my claims" and "unassigned". */
    @GetMapping("/summary")
    public QueueSummary summary(@AuthenticationPrincipal AuthenticatedUser me) {
        return queries.queueSummary(me.id(), me.role() == com.lodge.claims.domain.Role.OFFICER);
    }

    @PostMapping("/{id}/assign")
    public ClaimDetailResponse assign(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me) {
        commands.assign(id, me.id());
        return queries.detail(id, me);
    }

    @PostMapping("/{id}/info-requests")
    public ClaimDetailResponse requestInfo(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me,
                                           @Valid @RequestBody InfoRequestRequest req) {
        commands.requestInfo(id, me.id(), req.question());
        return queries.detail(id, me);
    }

    @PostMapping("/{id}/approve")
    public ClaimDetailResponse approve(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me,
                                       @Valid @RequestBody ApproveRequest req) {
        commands.approve(id, me.id(), req.reserveAmount());
        return queries.detail(id, me);
    }

    @PostMapping("/{id}/reject")
    public ClaimDetailResponse reject(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me,
                                      @Valid @RequestBody RejectRequest req) {
        commands.reject(id, me.id(), req.reason());
        return queries.detail(id, me);
    }

    @PostMapping("/{id}/settle")
    public ClaimDetailResponse settle(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me,
                                      @Valid @RequestBody SettleRequest req) {
        commands.settle(id, me.id(), req.amount());
        return queries.detail(id, me);
    }

    @PatchMapping("/{id}/reserve")
    public ClaimDetailResponse reserve(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me,
                                       @Valid @RequestBody ReserveRequest req) {
        commands.adjustReserve(id, me.id(), req.reserveAmount());
        return queries.detail(id, me);
    }
}
