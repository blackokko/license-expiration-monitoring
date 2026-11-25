package com.compsych.licmon.service;

import com.compsych.licmon.domain.MonitorRun;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Is the monitor actually doing its job?" Always judged against the real {@code today} (from the
 * injected Clock), never against a caller-supplied as-of date: health is a fact about the present.
 *
 * <p>Unhealthy when any of these hold:
 * <ul>
 *   <li>no successful run has ever happened;</li>
 *   <li>the latest successful run for a date on or before today is more than {@code maxDaysBehind}
 *       days old (stale: licenses may be crossing windows unseen);</li>
 *   <li>any successful run is dated after today. Its surfacings sit in the ledger and suppress the
 *       real alerts when those dates actually arrive, so this is a data-integrity problem, not
 *       "extra fresh".</li>
 * </ul>
 * A failed most-recent run is a warning, not unhealthy by itself: if an earlier run today
 * succeeded, nothing has been missed yet. Staleness catches it if failures continue.
 */
public final class HealthCheck {

    public static final int DEFAULT_MAX_DAYS_BEHIND = 1;

    private final int maxDaysBehind;

    public HealthCheck(int maxDaysBehind) {
        if (maxDaysBehind < 0) throw new IllegalArgumentException("maxDaysBehind must be >= 0");
        this.maxDaysBehind = maxDaysBehind;
    }

    public record Health(boolean healthy, LocalDate today, LocalDate lastSuccessfulAsOf,
                         Long daysSinceLastSuccessfulRun, List<LocalDate> futureDatedRuns,
                         MonitorRun lastRun, List<String> problems, List<String> warnings) {
    }

    /** @param runs in execution order, as the store returns them */
    public Health evaluate(List<MonitorRun> runs, LocalDate today) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        List<LocalDate> future = runs.stream()
                .filter(r -> r.status() == MonitorRun.Status.SUCCEEDED && r.asOf().isAfter(today))
                .map(MonitorRun::asOf).distinct().sorted().toList();
        if (!future.isEmpty()) {
            problems.add("successful run(s) dated after today " + today + ": " + future
                    + "; their surfacings will suppress real alerts on those dates");
        }

        // Only runs for today or earlier count toward freshness; a future run must not mask staleness.
        Optional<MonitorRun> lastOk = runs.stream()
                .filter(r -> r.status() == MonitorRun.Status.SUCCEEDED && !r.asOf().isAfter(today))
                .max((x, y) -> x.asOf().compareTo(y.asOf()));
        Long daysSince = lastOk.map(r -> ChronoUnit.DAYS.between(r.asOf(), today)).orElse(null);
        if (lastOk.isEmpty()) {
            problems.add("no successful run on or before " + today);
        } else if (daysSince > maxDaysBehind) {
            problems.add("last successful run was for " + lastOk.get().asOf() + ", " + daysSince
                    + " days ago (limit " + maxDaysBehind + ")");
        }

        MonitorRun lastRun = runs.isEmpty() ? null : runs.get(runs.size() - 1);
        if (lastRun != null && lastRun.status() == MonitorRun.Status.FAILED) {
            warnings.add("most recent run (" + lastRun.runId() + ", as of " + lastRun.asOf()
                    + ") failed: " + lastRun.error());
        }

        return new Health(problems.isEmpty(), today, lastOk.map(MonitorRun::asOf).orElse(null),
                daysSince, future, lastRun, List.copyOf(problems), List.copyOf(warnings));
    }
}
