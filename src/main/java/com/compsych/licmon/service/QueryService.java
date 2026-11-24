package com.compsych.licmon.service;

import com.compsych.licmon.domain.License;
import com.compsych.licmon.domain.LicenseView;
import com.compsych.licmon.domain.Window;
import com.compsych.licmon.domain.WindowPolicy;
import com.compsych.licmon.store.Store;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pure reads. Windows are derived on the fly from expiration + asOf + policy; never stored. */
public final class QueryService {

    private final Store store;
    private final WindowPolicy policy;

    public QueryService(Store store, WindowPolicy policy) {
        this.store = store;
        this.policy = policy;
    }

    /** Every window present as a key (possibly empty), licenses sorted by expiration then key. */
    public Map<Window, List<LicenseView>> byWindow(LocalDate asOf) {
        Map<Window, List<LicenseView>> out = new EnumMap<>(Window.class);
        for (Window w : Window.values()) out.put(w, new ArrayList<>());
        List<License> sorted = new ArrayList<>(store.licenses());
        sorted.sort(Comparator.comparing(License::licenseExpiration).thenComparing(License::key));
        for (License l : sorted) {
            Window w = policy.classify(l.licenseExpiration(), asOf);
            out.get(w).add(LicenseView.of(l, w));
        }
        return out;
    }

    public Optional<LicenseView> view(String state, String number, LocalDate asOf) {
        return store.license(License.keyOf(state, number))
                .map(l -> LicenseView.of(l, policy.classify(l.licenseExpiration(), asOf)));
    }
}
