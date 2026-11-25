package com.compsych.licmon.cli;

import com.compsych.licmon.Json;
import com.compsych.licmon.domain.License;
import com.compsych.licmon.domain.LicenseView;
import com.compsych.licmon.domain.MonitorRun;
import com.compsych.licmon.domain.Provider;
import com.compsych.licmon.domain.Surfacing;
import com.compsych.licmon.domain.Window;
import com.compsych.licmon.domain.WindowPolicy;
import com.compsych.licmon.service.HealthCheck;
import com.compsych.licmon.service.IntakeService;
import com.compsych.licmon.service.MonitorService;
import com.compsych.licmon.service.QueryService;
import com.compsych.licmon.store.InMemoryStore;
import com.compsych.licmon.store.SnapshotFile;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CLI front end. "Today" comes from the injected Clock in the configured zone; every command that
 * depends on the date accepts {@code --as-of YYYY-MM-DD}, which is how time is moved for testing
 * and the demo (never by touching the system clock).
 */
public final class Main {

    private static final String USAGE = """
            usage: licmon [--state FILE] [--config FILE] <command> [args]
              ingest <file.csv> [--as-of D]     load/upsert intake rows (D = date the data is known)
              run    [--as-of D] [--allow-future]  surface newly-entered windows (idempotent);
                                                future dates refused unless --allow-future
              query  [--as-of D] [--window W]   list licenses by window (JSON lines)
              show   <state> <number> [--as-of D]
              history <state> <number>          surfacings for one license
              status [--as-of D]                health vs real today (exit 4 if unhealthy);
                                                --as-of only moves the window counts
              demo   [<state> <number>]         walk one license OK -> EXPIRED on a scratch copy
            defaults: --state data/state.json  --config config/windows.json  --as-of today""";

    private final PrintStream out;
    private final Clock clock;

    public Main(PrintStream out, Clock clock) {
        this.out = out;
        this.clock = clock;
    }

    public static void main(String[] args) {
        System.exit(new Main(System.out, Clock.systemUTC()).execute(args));
    }

    public int execute(String[] argv) {
        try {
            Args a = Args.parse(argv);
            if (a.positional.isEmpty()) {
                out.println(USAGE);
                return 1;
            }
            WindowPolicy policy = WindowPolicy.load(Path.of(a.opt("config", "config/windows.json")));
            SnapshotFile file = new SnapshotFile(Path.of(a.opt("state", "data/state.json")));
            InMemoryStore store = file.load();
            LocalDate today = LocalDate.now(clock.withZone(policy.zone()));
            LocalDate asOf = a.options.containsKey("as-of") ? LocalDate.parse(a.opt("as-of", null)) : today;
            String cmd = a.positional.get(0);
            List<String> rest = a.positional.subList(1, a.positional.size());

            return switch (cmd) {
                case "ingest" -> ingest(store, file, rest, asOf);
                case "run" -> run(store, file, policy, today, asOf, a.flags.contains("allow-future"));
                case "query" -> query(store, policy, asOf, a.options.get("window"));
                case "show" -> show(store, policy, asOf, rest);
                case "history" -> history(store, rest);
                case "status" -> status(store, policy, today, asOf);
                case "demo" -> demo(store, policy, today, rest);
                default -> {
                    out.println("unknown command: " + cmd);
                    out.println(USAGE);
                    yield 1;
                }
            };
        } catch (IOException | RuntimeException e) {
            out.println("error: " + e.getMessage());
            return 1;
        }
    }

    private int ingest(InMemoryStore store, SnapshotFile file, List<String> rest, LocalDate asOf) throws IOException {
        need(rest, 1, "ingest <file.csv>");
        IntakeService.Result r;
        try (Reader in = Files.newBufferedReader(Path.of(rest.get(0)), StandardCharsets.UTF_8)) {
            r = new IntakeService(store, clock).ingestCsv(in, asOf);
        }
        file.save(store);
        printJson(r);
        return r.errors().isEmpty() ? 0 : 3;
    }

    private int run(InMemoryStore store, SnapshotFile file, WindowPolicy policy, LocalDate today,
                    LocalDate asOf, boolean allowFuture) throws IOException {
        if (asOf.isAfter(today) && !allowFuture) {
            // A future-dated run writes surfacings that would suppress the real alerts when those
            // dates arrive. Refuse unless explicitly asked (e.g. against a scratch --state file).
            out.println("error: refusing to run for " + asOf + ", after today " + today
                    + ". Surfacings from a future run would suppress real alerts on those dates."
                    + " Use 'demo', or a scratch --state file with --allow-future.");
            return 5;
        }
        warnIfBehindWatermark(store, asOf);
        MonitorService.RunResult r = new MonitorService(store, policy, clock).run(asOf);
        file.save(store);
        for (Surfacing s : r.surfaced()) printJson(s);
        printJson(r.run());
        return r.run().status() == MonitorRun.Status.SUCCEEDED ? 0 : 2;
    }

    private int query(InMemoryStore store, WindowPolicy policy, LocalDate asOf, String window) throws JsonProcessingException {
        Map<Window, List<LicenseView>> byWindow = new QueryService(store, policy).byWindow(asOf);
        if (window != null) {
            for (LicenseView v : byWindow.get(Window.valueOf(window.toUpperCase()))) printJson(v);
        } else {
            for (List<LicenseView> vs : byWindow.values()) for (LicenseView v : vs) printJson(v);
        }
        return 0;
    }

    private int show(InMemoryStore store, WindowPolicy policy, LocalDate asOf, List<String> rest) throws JsonProcessingException {
        need(rest, 2, "show <state> <number>");
        Optional<LicenseView> v = new QueryService(store, policy).view(rest.get(0), rest.get(1), asOf);
        if (v.isEmpty()) {
            out.println("not found");
            return 1;
        }
        printJson(v.get());
        return 0;
    }

    private int history(InMemoryStore store, List<String> rest) throws JsonProcessingException {
        need(rest, 2, "history <state> <number>");
        for (Surfacing s : store.surfacings(License.keyOf(rest.get(0), rest.get(1)))) printJson(s);
        return 0;
    }

    /**
     * Health is judged against the real {@code today}; {@code --as-of} only changes which date the
     * window counts are computed for. Exit code 4 when unhealthy, so cron/monitoring can alert on it.
     */
    private int status(InMemoryStore store, WindowPolicy policy, LocalDate today, LocalDate asOf) throws JsonProcessingException {
        HealthCheck.Health h = new HealthCheck(HealthCheck.DEFAULT_MAX_DAYS_BEHIND).evaluate(store.runs(), today);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("healthy", h.healthy());
        s.put("problems", h.problems());
        s.put("warnings", h.warnings());
        s.put("today", today.toString());
        s.put("last_successful_as_of", h.lastSuccessfulAsOf() == null ? null : h.lastSuccessfulAsOf().toString());
        s.put("days_since_last_successful_run", h.daysSinceLastSuccessfulRun());
        s.put("future_dated_runs", h.futureDatedRuns().stream().map(LocalDate::toString).toList());
        s.put("last_run", h.lastRun());
        s.put("policy", policy.version());
        s.put("licenses", store.licenses().size());
        s.put("counts_as_of", asOf.toString());
        Map<String, Integer> counts = new LinkedHashMap<>();
        new QueryService(store, policy).byWindow(asOf).forEach((w, vs) -> counts.put(w.name(), vs.size()));
        s.put("counts", counts);
        printJson(s);
        return h.healthy() ? 0 : 4;
    }

    /**
     * Walks one license through every window by running the real MonitorService at each window's
     * entry date. Runs on a scratch store containing only that license, so the real ledger and
     * watermark are untouched. Each date is run twice to show the repeat produces nothing.
     */
    private int demo(InMemoryStore store, WindowPolicy policy, LocalDate today, List<String> rest) throws JsonProcessingException {
        License license;
        Provider provider;
        if (rest.size() >= 2) {
            Optional<License> found = store.license(License.keyOf(rest.get(0), rest.get(1)));
            if (found.isEmpty()) {
                out.println("not found");
                return 1;
            }
            license = found.get();
            provider = store.provider(license.npi()).orElse(null);
        } else {
            license = new License("1234567890", "NY-44821", "NY", today.plusDays(120), today, clock.instant());
            provider = new Provider("1234567890", "Demo", "Provider", clock.instant());
        }
        InMemoryStore scratch = new InMemoryStore();
        if (provider != null) scratch.putProvider(provider);
        scratch.putLicense(license);
        MonitorService monitor = new MonitorService(scratch, policy, clock);
        QueryService query = new QueryService(scratch, policy);

        out.println("policy " + policy.version() + ", license " + license.key()
                + " expires " + license.licenseExpiration());
        for (Window w : Window.values()) {
            LocalDate d = policy.entryDate(w, license.licenseExpiration());
            LicenseView v = query.view(license.licenseState(), license.licenseNumber(), d).orElseThrow();
            MonitorService.RunResult first = monitor.run(d);
            MonitorService.RunResult again = monitor.run(d);
            out.println();
            out.println("as_of " + d + "  ->  " + Json.MAPPER.writeValueAsString(v));
            out.println("  run:    surfaced " + first.surfaced().size()
                    + (first.surfaced().isEmpty() ? "" : " " + first.surfaced().get(0).window()));
            out.println("  re-run: surfaced " + again.surfaced().size());
        }
        return 0;
    }

    private void warnIfBehindWatermark(InMemoryStore store, LocalDate asOf) {
        store.runs().stream()
                .filter(r -> r.status() == MonitorRun.Status.SUCCEEDED && r.asOf().isAfter(asOf))
                .findAny()
                .ifPresent(r -> out.println("warning: a run already succeeded for later date " + r.asOf()
                        + "; this run can only surface licenses not yet surfaced at least this severely"));
    }

    private void printJson(Object o) throws JsonProcessingException {
        out.println(Json.MAPPER.writeValueAsString(o));
    }

    private static void need(List<String> rest, int n, String usage) {
        if (rest.size() < n) throw new IllegalArgumentException("usage: " + usage);
    }

    /** --key value options and --flag switches anywhere; everything else positional. */
    static final class Args {
        static final Set<String> FLAGS = Set.of("allow-future");

        final Map<String, String> options = new HashMap<>();
        final Set<String> flags = new HashSet<>();
        final List<String> positional = new ArrayList<>();

        String opt(String k, String def) {
            return options.getOrDefault(k, def);
        }

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                if (argv[i].startsWith("--") && FLAGS.contains(argv[i].substring(2))) {
                    a.flags.add(argv[i].substring(2));
                } else if (argv[i].startsWith("--")) {
                    if (i + 1 >= argv.length) throw new IllegalArgumentException("missing value for " + argv[i]);
                    a.options.put(argv[i].substring(2), argv[++i]);
                } else {
                    a.positional.add(argv[i]);
                }
            }
            return a;
        }
    }
}
