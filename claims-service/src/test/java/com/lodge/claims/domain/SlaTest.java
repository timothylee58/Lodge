package com.lodge.claims.domain;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class SlaTest {
    final Instant since = Instant.parse("2026-11-20T00:00:00Z");
    final Duration day = Duration.ofHours(24);

    @Test
    void onTrackThenDueSoonAtThreeQuartersThenBreachedAtTheTarget() {
        assertThat(Sla.assess(day, since, since.plus(Duration.ofHours(17))).state()).isEqualTo(SlaState.ON_TRACK);
        assertThat(Sla.assess(day, since, since.plus(Duration.ofHours(18))).state()).isEqualTo(SlaState.DUE_SOON);
        assertThat(Sla.assess(day, since, since.plus(day)).state()).as("due exactly now = breached").isEqualTo(SlaState.BREACHED);
        assertThat(Sla.assess(day, since, since.plus(day)).dueAt()).isEqualTo(since.plus(day));
    }

    @Test
    void noTargetMeansNone() {
        assertThat(Sla.assess(null, since, since)).isEqualTo(Sla.NONE);
    }
}
