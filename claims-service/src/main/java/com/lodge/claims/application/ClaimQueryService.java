package com.lodge.claims.application;

import com.lodge.claims.api.dto.*;
import com.lodge.claims.domain.*;
import com.lodge.claims.infrastructure.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ClaimQueryService {
    // Reserves are internal exposure figures: claimants never see them, nor the events that carry them.
    private static final Set<String> INTERNAL_EVENTS = Set.of("RESERVE_CHANGED");
    private static final Set<String> INTERNAL_KEYS = Set.of("reserveAmount", "from", "to");

    private final ClaimRepository claims;
    private final ClaimEventRepository events;
    private final InfoRequestRepository infoRequests;
    private final UserRepository users;
    private final PolicyRepository policies;
    private final SlaPolicy sla;
    private final NamedParameterJdbcTemplate jdbc;

    public ClaimQueryService(ClaimRepository claims, ClaimEventRepository events, InfoRequestRepository infoRequests,
                             UserRepository users, PolicyRepository policies, SlaPolicy sla,
                             NamedParameterJdbcTemplate jdbc) {
        this.sla = sla;
        this.jdbc = jdbc;
        this.claims = claims; this.events = events; this.infoRequests = infoRequests;
        this.users = users; this.policies = policies;
    }

    public List<PolicyResponse> myPolicies(UUID claimantId) {
        return policies.findByHolderId(claimantId).stream()
                .map(p -> new PolicyResponse(p.getId(), p.getPolicyNumber(), p.getType().name(), p.getMarket().name(),
                        p.getCoverageLimit(), p.getCurrency())).toList();
    }

    public List<ClaimSummary> mine(UUID claimantId) {
        var list = claims.findByClaimantIdOrderByCreatedAtDesc(claimantId);
        return summaries(list, false);
    }

    public PageResponse<ClaimSummary> queue(ClaimStatus status, String assignee, UUID me, int page, int size, String sort) {
        Specification<Claim> spec = Specification.where(null);
        if (status != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        if ("unassigned".equalsIgnoreCase(assignee)) spec = spec.and((r, q, cb) -> cb.isNull(r.get("assigneeId")));
        else if ("me".equalsIgnoreCase(assignee)) spec = spec.and((r, q, cb) -> cb.equal(r.get("assigneeId"), me));
        else if (assignee != null && !assignee.isBlank()) {
            UUID id;
            try { id = UUID.fromString(assignee); }
            catch (IllegalArgumentException e) { throw new InvalidRequestException("assignee must be me, unassigned or a user id"); }
            spec = spec.and((r, q, cb) -> cb.equal(r.get("assigneeId"), id));
        }
        var result = claims.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                "waiting".equalsIgnoreCase(sort)
                        ? Sort.by("statusChangedAt").ascending().and(Sort.by("createdAt")) // longest in current status
                        : Sort.by("createdAt").ascending())); // default: oldest first = fair queue
        return new PageResponse<>(summaries(result.getContent(), true), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    public ClaimDetailResponse detail(UUID claimId, AuthenticatedUser me) {
        var claim = claims.findById(claimId).orElseThrow(() -> new EntityNotFoundException("Claim " + claimId));
        boolean staff = me.role() != Role.CLAIMANT;
        // Not yours == doesn't exist, for claimants.
        if (!staff && !claim.getClaimantId().equals(me.id())) throw new EntityNotFoundException("Claim " + claimId);

        var raw = events.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                .filter(e -> staff || !INTERNAL_EVENTS.contains(e.getType())).toList();
        Map<UUID, AppUser> actors = users.findAllById(raw.stream().map(ClaimEvent::getActorId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AppUser::getId, u -> u));
        var timeline = raw.stream().map(e -> staff
                        ? new TimelineEntry(e.getType(), e.getCreatedAt(), e.getActorId(), nameOf(actors.get(e.getActorId())), e.getPayload())
                        : new TimelineEntry(e.getType(), e.getCreatedAt(), null, roleLabel(actors.get(e.getActorId()), me.id()), sanitise(e.getPayload())))
                .toList();
        var infos = infoRequests.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                .map(i -> new InfoRequestView(i.getId(), i.getQuestion(), i.getResponse(), i.getStatus().name(),
                        i.getCreatedAt(), i.getAnsweredAt())).toList();
        return new ClaimDetailResponse(summaries(List.of(claim), staff).get(0), timeline, infos);
    }

    /**
     * One SQL aggregate, whatever the volume (was: load every open claim and count in Java). The bucket rules mirror
     * Sla.assess exactly - BREACHED at the target, DUE_SOON at 75% - and a test runs both over the same claims to keep
     * them in step. "now" comes from the application clock so the summary and the per-row chips agree.
     */
    public QueueSummary queueSummary(UUID me, boolean officer) {
        var p = new MapSqlParameterSource()
                .addValue("me", officer ? me : null)
                .addValue("now", java.sql.Timestamp.from(sla.now()))
                .addValue("sub", sla.targetSeconds(ClaimStatus.SUBMITTED))
                .addValue("rev", sla.targetSeconds(ClaimStatus.UNDER_REVIEW))
                .addValue("pi", sla.targetSeconds(ClaimStatus.PENDING_INFO))
                .addValue("ap", sla.targetSeconds(ClaimStatus.APPROVED));
        var rows = jdbc.query(QUEUE_SUMMARY_SQL, p, (rs, i) -> Map.entry(rs.getString("slice"), new SlaSummary(
                rs.getLong("breached"), rs.getLong("due_soon"), rs.getLong("on_track"), rs.getLong("chase"))));
        var bySlice = rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var empty = new SlaSummary(0, 0, 0, 0);
        return new QueueSummary(bySlice.getOrDefault("mine", empty), bySlice.getOrDefault("unassigned", empty));
    }

    private static final String QUEUE_SUMMARY_SQL = """
            WITH open_claims AS (
              SELECT status,
                     CASE WHEN assignee_id = CAST(:me AS uuid) THEN 'mine' ELSE 'unassigned' END AS slice,
                     sla_started_at,
                     make_interval(secs => CASE status WHEN 'SUBMITTED' THEN :sub WHEN 'UNDER_REVIEW' THEN :rev
                                                       WHEN 'PENDING_INFO' THEN :pi ELSE :ap END) AS target
              FROM claim
              WHERE (assignee_id = CAST(:me AS uuid) AND status IN ('SUBMITTED','UNDER_REVIEW','PENDING_INFO','APPROVED'))
                 OR (assignee_id IS NULL AND status = 'SUBMITTED')
            ), bucketed AS (
              SELECT slice, status,
                     CASE WHEN :now >= sla_started_at + target        THEN 'BREACHED'
                          WHEN :now >= sla_started_at + target * 0.75 THEN 'DUE_SOON'
                          ELSE 'ON_TRACK' END AS bucket
              FROM open_claims
            )
            SELECT slice,
                   count(*) FILTER (WHERE bucket = 'BREACHED' AND status <> 'PENDING_INFO') AS breached,
                   count(*) FILTER (WHERE bucket = 'DUE_SOON')                              AS due_soon,
                   count(*) FILTER (WHERE bucket = 'ON_TRACK')                              AS on_track,
                   count(*) FILTER (WHERE bucket = 'BREACHED' AND status = 'PENDING_INFO')  AS chase
            FROM bucketed GROUP BY slice
            """;

    private static String nameOf(AppUser u) { return u == null ? null : u.getName(); }

    /** What a claimant may know about who acted: themselves, "a claims officer", or the company. Never an id or name. */
    private static String roleLabel(AppUser u, UUID claimantId) {
        if (u == null) return "Lodge";
        if (u.getId().equals(claimantId)) return "You";
        return u.getRole() == Role.OFFICER ? "Claims officer" : "Lodge";
    }

    private static Map<String, Object> sanitise(Map<String, Object> payload) {
        var out = new LinkedHashMap<>(payload);
        out.keySet().removeAll(INTERNAL_KEYS);
        return out;
    }

    private List<ClaimSummary> summaries(List<Claim> list, boolean showReserve) {
        var ids = list.stream().map(Claim::getAssigneeId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = users.findAllById(ids).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getName));
        return list.stream().map(c -> new ClaimSummary(c.getId(), c.getClaimNumber(), c.getStatus().name(),
                c.getType().name(), c.getMarket().name(), c.getIncidentDate(), c.getDescription(),
                showReserve ? c.getReserveAmount() : null, c.getSettledAmount(),
                c.getCurrency(), showReserve ? c.getAssigneeId() : null,
                showReserve ? names.get(c.getAssigneeId()) : (c.getAssigneeId() == null ? null : "A claims officer"), c.getCreatedAt(), c.getUpdatedAt(),
                c.getVersion(), showReserve ? c.getStatusChangedAt() : null,
                showReserve ? sla.assess(c).state().name() : null, showReserve ? sla.assess(c).dueAt() : null)).toList();
    }
}
