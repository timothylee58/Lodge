package com.lodge.claims.domain;

import java.time.Duration;
import java.time.Instant;

/** Pure SLA arithmetic. DUE_SOON once 75% of the target has elapsed. */
public record Sla(SlaState state, Instant dueAt) {
    public static final Sla NONE = new Sla(SlaState.NONE, null);

    public static Sla assess(Duration target, Instant since, Instant now) {
        if (target == null || since == null) return NONE;
        var due = since.plus(target);
        if (!now.isBefore(due)) return new Sla(SlaState.BREACHED, due);
        var elapsed = Duration.between(since, now);
        return new Sla(elapsed.compareTo(target.multipliedBy(3).dividedBy(4)) >= 0 ? SlaState.DUE_SOON : SlaState.ON_TRACK, due);
    }
}
