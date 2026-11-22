package com.claimdesk.claims.application;

import com.claimdesk.claims.api.dto.*;
import com.claimdesk.claims.domain.*;
import com.claimdesk.claims.infrastructure.*;
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

    public ClaimQueryService(ClaimRepository claims, ClaimEventRepository events, InfoRequestRepository infoRequests,
                             UserRepository users, PolicyRepository policies) {
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

    public PageResponse<ClaimSummary> queue(ClaimStatus status, String assignee, UUID me, int page, int size) {
        Specification<Claim> spec = Specification.where(null);
        if (status != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        if ("unassigned".equalsIgnoreCase(assignee)) spec = spec.and((r, q, cb) -> cb.isNull(r.get("assigneeId")));
        else if ("me".equalsIgnoreCase(assignee)) spec = spec.and((r, q, cb) -> cb.equal(r.get("assigneeId"), me));
        else if (assignee != null && !assignee.isBlank()) {
            var id = UUID.fromString(assignee); // bad value -> IllegalArgumentException -> 400
            spec = spec.and((r, q, cb) -> cb.equal(r.get("assigneeId"), id));
        }
        var result = claims.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by("createdAt").ascending())); // oldest first = fair queue
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
                c.getVersion())).toList();
    }
}
