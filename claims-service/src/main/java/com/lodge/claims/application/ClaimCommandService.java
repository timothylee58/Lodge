package com.lodge.claims.application;

import com.lodge.claims.domain.*;
import com.lodge.claims.infrastructure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * All claim state changes go through here. Every command is ONE transaction that writes:
 * the claim, a ClaimEvent (audit/timeline) and an OutboxEvent (Kafka). Ownership rules live here;
 * transition rules live in Claim/ClaimStatus.
 */
@Service
public class ClaimCommandService {
    private final ClaimRepository claims;
    private final OutboxRepository outbox;
    private final ClaimEventRepository events;
    private final InfoRequestRepository infoRequests;
    private final PolicyRepository policies;
    private final UserRepository users;
    private final EntityManager em;
    private final ObjectMapper json;
    private final String topic;
    private final java.time.Clock clock;

    public ClaimCommandService(ClaimRepository claims, OutboxRepository outbox, ClaimEventRepository events,
                               InfoRequestRepository infoRequests, PolicyRepository policies, UserRepository users,
                               EntityManager em, ObjectMapper json,
                               @Value("${lodge.outbox.topic}") String topic, java.time.Clock clock) {
        this.clock = clock;
        this.claims = claims; this.outbox = outbox; this.events = events; this.infoRequests = infoRequests;
        this.policies = policies; this.users = users; this.em = em; this.json = json; this.topic = topic;
    }

    @Transactional
    public Claim submit(UUID claimantId, UUID policyId, LocalDate incidentDate, String description) {
        // Someone else's policy looks identical to a missing one: don't leak which policy ids exist.
        var policy = policies.findById(policyId)
                .filter(p -> p.getHolderId().equals(claimantId))
                .orElseThrow(() -> new EntityNotFoundException("Policy " + policyId));
        if (!policy.isActive()) throw new InvalidRequestException("Policy is not active");
        // "Future" in the policy's market, not on the server's UTC clock (MY/SG/AU are ahead of UTC).
        if (incidentDate.isAfter(policy.getMarket().today(clock)))
            throw new InvalidRequestException("Incident date is in the future");

        var number = "CLM-" + em.createNativeQuery("select nextval('claim_number_seq')").getSingleResult();
        var claim = claims.saveAndFlush(Claim.submit(number, policy.getId(), claimantId, policy.getType(),
                policy.getMarket(), incidentDate, description, policy.getCurrency()));
        record(claim, "CLAIM_SUBMITTED", claimantId, Map.of("policyNumber", policy.getPolicyNumber()));
        return claim;
    }

    @Transactional
    public Claim assign(UUID claimId, UUID officerId) {
        var claim = load(claimId);
        claim.assignTo(officerId);
        flushAndRecord(claim, "CLAIM_ASSIGNED", officerId, Map.of());
        return claim;
    }

    @Transactional
    public Claim requestInfo(UUID claimId, UUID officerId, String question) {
        var claim = loadOwned(claimId, officerId);
        claim.requestInfo();
        infoRequests.save(new InfoRequest(claimId, question, officerId));
        flushAndRecord(claim, "INFO_REQUESTED", officerId, Map.of("question", question));
        return claim;
    }

    @Transactional
    public Claim respondToInfo(UUID claimId, UUID claimantId, String response) {
        var claim = load(claimId);
        if (!claim.getClaimantId().equals(claimantId)) throw new EntityNotFoundException("Claim " + claimId);
        claim.infoProvided(); // 409 unless PENDING_INFO
        var open = infoRequests.findFirstByClaimIdAndStatus(claimId, InfoRequestStatus.OPEN)
                .orElseThrow(() -> new BusinessConflictException("No open information request"));
        open.answer(response);
        flushAndRecord(claim, "INFO_PROVIDED", claimantId, Map.of("response", response));
        return claim;
    }

    @Transactional
    public Claim approve(UUID claimId, UUID officerId, BigDecimal reserve) {
        var claim = loadOwned(claimId, officerId);
        requireWithinLimit(claim, reserve, "Reserve");
        claim.approve(reserve);
        flushAndRecord(claim, "CLAIM_APPROVED", officerId, Map.of("reserveAmount", reserve));
        return claim;
    }

    @Transactional
    public Claim reject(UUID claimId, UUID officerId, String reason) {
        var claim = loadOwned(claimId, officerId);
        claim.reject();
        flushAndRecord(claim, "CLAIM_REJECTED", officerId, Map.of("reason", reason));
        return claim;
    }

    @Transactional
    public Claim settle(UUID claimId, UUID officerId, BigDecimal amount) {
        var claim = loadOwned(claimId, officerId);
        var limit = policies.findById(claim.getPolicyId()).orElseThrow().getCoverageLimit();
        claim.settle(amount, limit);
        flushAndRecord(claim, "CLAIM_SETTLED", officerId, Map.of("settledAmount", amount));
        return claim;
    }

    @Transactional
    public Claim adjustReserve(UUID claimId, UUID officerId, BigDecimal reserve) {
        var claim = loadOwned(claimId, officerId);
        requireWithinLimit(claim, reserve, "Reserve");
        var old = claim.getReserveAmount();
        claim.adjustReserve(reserve);
        flushAndRecord(claim, "RESERVE_CHANGED", officerId, Map.of("from", old, "to", reserve));
        return claim;
    }

    // ---- helpers ----

    private Claim load(UUID id) {
        return claims.findById(id).orElseThrow(() -> new EntityNotFoundException("Claim " + id));
    }

    private Claim loadOwned(UUID id, UUID officerId) {
        var claim = load(id);
        if (!claim.isAssignedTo(officerId)) throw new AccessDeniedException("Claim not assigned to you");
        return claim;
    }

    private void requireWithinLimit(Claim c, BigDecimal amount, String what) {
        var limit = policies.findById(c.getPolicyId()).orElseThrow().getCoverageLimit();
        if (amount != null && amount.compareTo(limit) > 0)
            throw new InvalidRequestException(what + " exceeds policy coverage limit");
    }

    private void flushAndRecord(Claim c, String type, UUID actor, Map<String, Object> extra) {
        claims.saveAndFlush(c); // flush so @Version is bumped; the event snapshot carries it for consumers
        record(c, type, actor, extra);
    }

    /** Timeline row + outbox row in the caller's transaction. The Kafka payload is a full snapshot so
     *  consumers never call back; version lets them ignore stale/out-of-order events. */
    private void record(Claim c, String type, UUID actor, Map<String, Object> extra) {
        events.save(new ClaimEvent(c.getId(), type, actor, new LinkedHashMap<>(extra)));
        try {
            var eventId = UUID.randomUUID();
            var p = new HashMap<String, Object>();
            p.put("eventId", eventId);
            p.put("eventType", type);
            p.put("occurredAt", Instant.now());
            p.put("claimId", c.getId());
            p.put("claimNumber", c.getClaimNumber());
            p.put("version", c.getVersion());
            p.put("status", c.getStatus());
            p.put("market", c.getMarket());
            p.put("type", c.getType());
            p.put("currency", c.getCurrency());
            p.put("reserveAmount", c.getReserveAmount());
            p.put("settledAmount", c.getSettledAmount());
            p.put("assigneeId", c.getAssigneeId());
            p.put("assigneeName", c.getAssigneeId() == null ? null
                    : users.findById(c.getAssigneeId()).map(AppUser::getName).orElse(null));
            outbox.save(new OutboxEvent(eventId, c.getId(), type, topic, json.writeValueAsString(p)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise event", e); // rolls back the claim change too
        }
    }
}
