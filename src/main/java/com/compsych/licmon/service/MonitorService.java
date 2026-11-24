package com.compsych.licmon.service;

import com.compsych.licmon.domain.License;
import com.compsych.licmon.domain.MonitorRun;
import com.compsych.licmon.domain.Surfacing;
import com.compsych.licmon.domain.Window;
import com.compsych.licmon.domain.WindowPolicy;
import com.compsych.licmon.store.Store;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The thing that watches. Each run evaluates <em>every</em> license against {@code asOf} (55k rows
 * is trivial), rather than looking only at what changed since the last run. That is what makes
 * missed runs harmless: whatever window a license is in today, if we haven't surfaced it (or
 * something more severe) for this expiration date, we surface it now.
 *
 * <p>Dedup rule: surface window W for (license, expiration) only if W is more severe than anything
 * already surfaced for that pair. This gives "no repeats", stays correct if a run is replayed for
 * an earlier date, and records any windows jumped over as {@code skippedWindows}.
 */
public final class MonitorService {

    private final Store store;
    private final WindowPolicy policy;
    private final Clock clock;

    public MonitorService(Store store, WindowPolicy policy, Clock clock) {
        this.store = store;
        this.policy = policy;
        this.clock = clock;
    }

    public record RunResult(MonitorRun run, List<Surfacing> surfaced) {
    }

    public RunResult run(LocalDate asOf) {
        String runId = UUID.randomUUID().toString();
        Instant started = clock.instant();
        List<Surfacing> surfaced = new ArrayList<>();
        int evaluated = 0;
        try {
            for (License l : store.licenses()) {
                evaluated++;
                evaluate(l, asOf, runId, started).ifPresent(surfaced::add);
            }
            surfaced.sort(Comparator.comparing(Surfacing::window).reversed()
                    .thenComparing(Surfacing::licenseExpiration)
                    .thenComparing(Surfacing::licenseKey));
            MonitorRun run = new MonitorRun(runId, asOf, started, clock.instant(),
                    MonitorRun.Status.SUCCEEDED, evaluated, surfaced.size(), null);
            store.commitRun(run, surfaced);
            return new RunResult(run, surfaced);
        } catch (RuntimeException e) {
            // Nothing from this run is kept except the record that it failed; a re-run redoes it all.
            MonitorRun failed = new MonitorRun(runId, asOf, started, clock.instant(),
                    MonitorRun.Status.FAILED, evaluated, 0, e.toString());
            store.commitRun(failed, List.of());
            return new RunResult(failed, List.of());
        }
    }

    private Optional<Surfacing> evaluate(License l, LocalDate asOf, String runId, Instant now) {
        Window current = policy.classify(l.licenseExpiration(), asOf);
        if (!current.isAlerting()) return Optional.empty();

        Window mostSevereSoFar = Window.OK;
        for (Surfacing s : store.surfacings(l.key())) {
            if (s.licenseExpiration().equals(l.licenseExpiration()) && s.window().moreSevereThan(mostSevereSoFar)) {
                mostSevereSoFar = s.window();
            }
        }
        if (!current.moreSevereThan(mostSevereSoFar)) return Optional.empty();

        final Window floor = mostSevereSoFar;
        List<Window> skipped = WindowPolicy.alertingWindowsBefore(current).stream()
                .filter(w -> w.moreSevereThan(floor))
                .toList();
        return Optional.of(new Surfacing(l.key(), l.npi(), l.licenseNumber(), l.licenseState(),
                current, l.licenseExpiration(), asOf, skipped, policy.version(), runId, now));
    }
}
