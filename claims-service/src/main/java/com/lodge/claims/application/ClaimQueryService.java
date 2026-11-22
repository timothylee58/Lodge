package com.lodge.claims.application;

import com.lodge.claims.api.dto.*;
import com.lodge.claims.domain.*;
import com.lodge.claims.infrastructure.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    public ClaimQueryService(ClaimRepository claims, ClaimEventRepository events, InfoRequestRepository infoRequests,
                             UserRepository users, PolicyRepository policies, SlaPolicy sla) {
        this.sla = sla;
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

        var timeline = events.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                .filter(e -> staff || !INTERNAL_EVENTS.contains(e.getType()))
                .map(e -> new TimelineEntry(e.getType(), e.getCreatedAt(), e.getActorId(), staff ? e.getPayload() : sanitise(e.getPayload())))
                .toList();
        var infos = infoRequests.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                .map(i -> new InfoRequestView(i.getId(), i.getQuestion(), i.getResponse(), i.getStatus().name(),
                        i.getCreatedAt(), i.getAnsweredAt())).toList();
        return new ClaimDetailResponse(summaries(List.of(claim), staff).get(0), timeline, infos);
    }

    /** Counts over OPEN claims only. Small scale: loads the slices; at volume this becomes a SQL aggregate. */
    public QueueSummary queueSummary(UUID me, boolean officer) {
        var open = java.util.EnumSet.of(ClaimStatus.SUBMITTED, ClaimStatus.UNDER_REVIEW, ClaimStatus.PENDING_INFO, ClaimStatus.APPROVED);
        Specification<Claim> mine = (r, q, cb) -> cb.and(cb.equal(r.get("assigneeId"), me), r.get("status").in(open));
        Specification<Claim> unassigned = (r, q, cb) -> cb.and(cb.isNull(r.get("assigneeId")), cb.equal(r.get("status"), ClaimStatus.SUBMITTED));
        return new QueueSummary(officer ? count(claims.findAll(mine)) : new SlaSummary(0, 0, 0), count(claims.findAll(unassigned)));
    }

    private SlaSummary count(List<Claim> list) {
        long b = 0, d = 0, o = 0;
        for (var c : list) {
            switch (sla.assess(c).state()) { case BREACHED -> b++; case DUE_SOON -> d++; case ON_TRACK -> o++; default -> { } }
        }
        return new SlaSummary(b, d, o);
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
                c.getCurrency(), c.getAssigneeId(), names.get(c.getAssigneeId()), c.getCreatedAt(), c.getUpdatedAt(),
                c.getVersion(), showReserve ? c.getStatusChangedAt() : null,
                showReserve ? sla.assess(c).state().name() : null, showReserve ? sla.assess(c).dueAt() : null)).toList();
    }
}
