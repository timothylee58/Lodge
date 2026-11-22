package com.lodge.reporting.api;

import com.lodge.reporting.projection.DeadLetterService;
import com.lodge.reporting.projection.DeadLetterService.RedriveResult;
import com.lodge.reporting.projection.DeadLetterService.Row;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** MANAGER only (SecurityConfig). */
@RestController
@RequestMapping("/api/ops/dead-letters")
public class DeadLetterController {
    private final DeadLetterService service;

    public DeadLetterController(DeadLetterService service) { this.service = service; }

    @GetMapping
    public List<Row> list() { return service.listUnresolved(); }

    /** 200 if applied; 422 if the message is still invalid (it stays listed, with last_error). */
    @PostMapping("/{id}/redrive")
    public ResponseEntity<RedriveResult> redrive(@PathVariable UUID id, Authentication auth) {
        var r = service.redrive(id, actor(auth));
        return ResponseEntity.status(r.redriven() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY).body(r);
    }

    @PostMapping("/redrive-all")
    public List<RedriveResult> redriveAll(Authentication auth) { return service.redriveAll(actor(auth)); }

    @PostMapping("/{id}/discard")
    public void discard(@PathVariable UUID id, Authentication auth) { service.discard(id, actor(auth)); }

    private static UUID actor(Authentication auth) {
        try { return UUID.fromString(String.valueOf(auth.getPrincipal())); } catch (IllegalArgumentException e) { return null; }
    }
}
