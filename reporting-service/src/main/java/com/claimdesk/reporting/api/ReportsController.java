package com.claimdesk.reporting.api;

import com.claimdesk.reporting.projection.ReportQueries;
import com.claimdesk.reporting.projection.ReportQueries.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;

/**
 * Eventually consistent by design. Every response says how fresh it is:
 * asOf = newest event reflected here, generatedAt = now. The UI shows "as of {asOf}".
 * Exposure is per market AND currency — no FX conversion (known gap, see docs).
 */
@RestController
@RequestMapping("/api/reports")
public class ReportsController {
    private final ReportQueries queries;

    public ReportsController(ReportQueries queries) { this.queries = queries; }

    public record ExposureReport(Instant asOf, Instant generatedAt, List<ExposureRow> rows, List<CurrencyTotal> totalsByCurrency) {}
    public record StatusReport(Instant asOf, Instant generatedAt, List<StatusRow> rows) {}
    public record WorkloadReport(Instant asOf, Instant generatedAt, long unassignedClaims, List<OfficerWorkload> officers) {}
    public record OfficerWorkload(java.util.UUID assigneeId, String assigneeName, long openClaims,
                                  long underReview, long pendingInfo, long approved) {}

    @GetMapping("/exposure")
    public ExposureReport exposure() {
        return new ExposureReport(queries.lastEventAt(), Instant.now(), queries.exposure(), queries.exposureByCurrency());
    }

    @GetMapping("/claims-by-status")
    public StatusReport claimsByStatus() {
        return new StatusReport(queries.lastEventAt(), Instant.now(), queries.claimsByStatus());
    }

    /** Officers ever assigned a claim, incl. those now at 0. A brand-new officer appears after their first assignment. */
    @GetMapping("/workload")
    public WorkloadReport workload() {
        var officers = queries.workload().stream().map(w -> new OfficerWorkload(w.assigneeId(), w.assigneeName(),
                w.openClaims(), w.underReview(), w.pendingInfo(), w.approved())).toList();
        return new WorkloadReport(queries.lastEventAt(), Instant.now(), queries.unassigned(), officers);
    }
}
