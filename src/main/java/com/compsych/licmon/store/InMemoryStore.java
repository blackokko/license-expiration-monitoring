package com.compsych.licmon.store;

import com.compsych.licmon.domain.License;
import com.compsych.licmon.domain.MonitorRun;
import com.compsych.licmon.domain.Provider;
import com.compsych.licmon.domain.Surfacing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class InMemoryStore implements Store {

    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final Map<String, License> licenses = new LinkedHashMap<>();
    private final Map<String, List<Surfacing>> surfacingsByLicense = new LinkedHashMap<>();
    /** Enforces the ledger's unique constraint (licenseKey, window, expiration). */
    private final Set<String> surfacingKeys = new HashSet<>();
    private final List<MonitorRun> runs = new ArrayList<>();

    @Override
    public Optional<Provider> provider(String npi) {
        return Optional.ofNullable(providers.get(npi));
    }

    @Override
    public void putProvider(Provider p) {
        providers.put(p.npi(), p);
    }

    @Override
    public Optional<License> license(String key) {
        return Optional.ofNullable(licenses.get(key));
    }

    @Override
    public void putLicense(License l) {
        licenses.put(l.key(), l);
    }

    @Override
    public Collection<License> licenses() {
        return Collections.unmodifiableCollection(licenses.values());
    }

    @Override
    public List<Surfacing> surfacings(String licenseKey) {
        return List.copyOf(surfacingsByLicense.getOrDefault(licenseKey, List.of()));
    }

    @Override
    public List<Surfacing> allSurfacings() {
        List<Surfacing> all = new ArrayList<>();
        surfacingsByLicense.values().forEach(all::addAll);
        return all;
    }

    @Override
    public void commitRun(MonitorRun run, List<Surfacing> newSurfacings) {
        // Validate the whole batch first so a violation leaves the store untouched.
        Set<String> batchKeys = new HashSet<>();
        for (Surfacing s : newSurfacings) {
            String k = uniqueKey(s);
            if (surfacingKeys.contains(k) || !batchKeys.add(k)) {
                throw new IllegalStateException("duplicate surfacing " + k);
            }
        }
        for (Surfacing s : newSurfacings) {
            surfacingKeys.add(uniqueKey(s));
            surfacingsByLicense.computeIfAbsent(s.licenseKey(), x -> new ArrayList<>()).add(s);
        }
        runs.add(run);
    }

    @Override
    public List<MonitorRun> runs() {
        return List.copyOf(runs);
    }

    private static String uniqueKey(Surfacing s) {
        return s.licenseKey() + "|" + s.window() + "|" + s.licenseExpiration();
    }

    // ---- snapshot support ----

    public Snapshot toSnapshot() {
        return new Snapshot(Snapshot.CURRENT_VERSION, List.copyOf(providers.values()),
                List.copyOf(licenses.values()), allSurfacings(), List.copyOf(runs));
    }

    public static InMemoryStore fromSnapshot(Snapshot snap) {
        InMemoryStore s = new InMemoryStore();
        if (snap == null) return s;
        if (snap.schemaVersion() != Snapshot.CURRENT_VERSION) {
            throw new IllegalStateException("unsupported snapshot schemaVersion " + snap.schemaVersion());
        }
        nullSafe(snap.providers()).forEach(s::putProvider);
        nullSafe(snap.licenses()).forEach(s::putLicense);
        for (Surfacing x : nullSafe(snap.surfacings())) {
            s.surfacingKeys.add(uniqueKey(x));
            s.surfacingsByLicense.computeIfAbsent(x.licenseKey(), k -> new ArrayList<>()).add(x);
        }
        s.runs.addAll(nullSafe(snap.runs()));
        return s;
    }

    private static <T> List<T> nullSafe(List<T> l) {
        return l == null ? List.of() : l;
    }
}
