package com.compsych.licmon;

import com.compsych.licmon.domain.MonitorRun;
import com.compsych.licmon.service.HealthCheck;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthCheckTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    final HealthCheck check = new HealthCheck(1);

    static MonitorRun ok(LocalDate asOf) {
        return new MonitorRun("ok-" + asOf, asOf, Instant.EPOCH, Instant.EPOCH, MonitorRun.Status.SUCCEEDED, 1, 0, null);
    }

    static MonitorRun failed(LocalDate asOf) {
        return new MonitorRun("fail-" + asOf, asOf, Instant.EPOCH, Instant.EPOCH, MonitorRun.Status.FAILED, 1, 0, "boom");
    }

    @Test
    void neverRunIsUnhealthy() {
        HealthCheck.Health h = check.evaluate(List.of(), TODAY);
        assertFalse(h.healthy());
        assertNull(h.daysSinceLastSuccessfulRun());
    }

    @Test
    void onlyFailedRunsIsUnhealthy() {
        assertFalse(check.evaluate(List.of(failed(TODAY)), TODAY).healthy());
    }

    @Test
    void ranTodayOrYesterdayIsHealthy() {
        HealthCheck.Health h = check.evaluate(List.of(ok(TODAY)), TODAY);
        assertTrue(h.healthy());
        assertEquals(0L, h.daysSinceLastSuccessfulRun());
        assertEquals(1L, check.evaluate(List.of(ok(TODAY.minusDays(1))), TODAY).daysSinceLastSuccessfulRun());
        assertTrue(check.evaluate(List.of(ok(TODAY.minusDays(1))), TODAY).healthy());
    }

    @Test
    void twoDaysBehindIsStale() {
        HealthCheck.Health h = check.evaluate(List.of(ok(TODAY.minusDays(2))), TODAY);
        assertFalse(h.healthy());
        assertEquals(2L, h.daysSinceLastSuccessfulRun());
        assertTrue(h.problems().get(0).contains("2 days ago"));
    }

    @Test
    void futureDatedRunIsUnhealthyEvenIfTodayAlsoRan() {
        LocalDate future = TODAY.plusDays(106);
        HealthCheck.Health h = check.evaluate(List.of(ok(TODAY), ok(future)), TODAY);
        assertFalse(h.healthy());
        assertEquals(List.of(future), h.futureDatedRuns());
        assertEquals(0L, h.daysSinceLastSuccessfulRun()); // never negative
        assertEquals(TODAY, h.lastSuccessfulAsOf());
    }

    @Test
    void futureRunDoesNotMaskStaleness() {
        HealthCheck.Health h = check.evaluate(List.of(ok(TODAY.minusDays(10)), ok(TODAY.plusDays(5))), TODAY);
        assertFalse(h.healthy());
        assertEquals(10L, h.daysSinceLastSuccessfulRun());
        assertEquals(2, h.problems().size()); // future-dated AND stale
    }

    @Test
    void replayOfAPastDateAfterTodaysRunDoesNotMakeItStale() {
        HealthCheck.Health h = check.evaluate(List.of(ok(TODAY), ok(TODAY.minusDays(30))), TODAY);
        assertTrue(h.healthy());
        assertEquals(TODAY, h.lastSuccessfulAsOf());
    }

    @Test
    void failedLatestRunIsAWarningWhileStillFresh() {
        HealthCheck.Health h = check.evaluate(List.of(ok(TODAY), failed(TODAY)), TODAY);
        assertTrue(h.healthy());
        assertEquals(1, h.warnings().size());
        assertTrue(h.warnings().get(0).contains("boom"));
    }

    @Test
    void failuresBecomeUnhealthyOnceStale() {
        HealthCheck.Health h = check.evaluate(
                List.of(ok(TODAY.minusDays(3)), failed(TODAY.minusDays(2)), failed(TODAY.minusDays(1)), failed(TODAY)), TODAY);
        assertFalse(h.healthy());
        assertEquals(1, h.warnings().size());
    }
}
