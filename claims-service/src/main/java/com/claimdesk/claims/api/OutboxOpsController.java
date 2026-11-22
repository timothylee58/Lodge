package com.claimdesk.claims.api;

import com.claimdesk.claims.application.OutboxOpsService;
import com.claimdesk.claims.application.OutboxOpsService.DeadRow;
import com.claimdesk.claims.infrastructure.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MANAGER only (SecurityConfig). Re-drive endpoints for events that could not be published to Kafka. */
@RestController
@RequestMapping("/api/ops/outbox")
public class OutboxOpsController {
    private final OutboxOpsService ops;

    public OutboxOpsController(OutboxOpsService ops) { this.ops = ops; }

    @GetMapping("/dead")
    public List<DeadRow> dead() { return ops.listDead(); }

    @PostMapping("/{id}/redrive")
    public DeadRow redrive(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me) {
        return ops.redrive(id, me.id());
    }

    @PostMapping("/redrive-all")
    public Map<String, Integer> redriveAll(@AuthenticationPrincipal AuthenticatedUser me) {
        return Map.of("redriven", ops.redriveAll(me.id()));
    }
}
