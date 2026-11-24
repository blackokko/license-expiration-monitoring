package com.compsych.licmon;

import com.compsych.licmon.domain.License;
import com.compsych.licmon.domain.MonitorRun;
import com.compsych.licmon.domain.Surfacing;
import com.compsych.licmon.domain.Window;
import com.compsych.licmon.domain.WindowPolicy;
import com.compsych.licmon.service.MonitorService;
import com.compsych.licmon.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.compsych.licmon.domain.Window.EXPIRED;
import static com.compsych.licmon.domain.Window.EXPIRING_30;
import static com.compsych.licmon.domain.Window.EXPIRING_60;
import static com.compsych.licmon.domain.Window.EXPIRING_90;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorServiceTest {

    static final WindowPolicy POLICY = new WindowPolicy(90, 60, 30, ZoneId.of("UTC"));
    static final LocalDate EXP = LocalDate.of(2027, 5, 30);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);

    InMemoryStore store;
    MonitorService monitor;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        monitor = new MonitorService(store, POLICY, CLOCK);
    }

    License lic(String state, String number, LocalDate exp) {
        License l = new License("1234567890", number, state, exp, CLOCK.instant());
        store.putLicense(l);
        return l;
    }

    List<Window> surfacedWindows(License l) {
        return store.surfacings(l.key()).stream().map(Surfacing::window).toList();
    }

    @Test
    void walksOkToExpiredSurfacingEachWindowOnce() {
        License l = lic("NY", "NY-44821", EXP);
        for (Window w : Window.values()) {
            LocalDate d = POLICY.entryDate(w, EXP);
            List<Surfacing> got = monitor.run(d).surfaced();
            if (w == Window.OK) {
                assertTrue(got.isEmpty());
            } else {
                assertEquals(1, got.size(), "entering " + w);
                assertEquals(w, got.get(0).window());
                assertTrue(got.get(0).skippedWindows().isEmpty());
            }
        }
        assertEquals(List.of(EXPIRING_90, EXPIRING_60, EXPIRING_30, EXPIRED), surfacedWindows(l));
    }

    @Test
    void sameDateRerunSurfacesNothing() {
        lic("NY", "NY-1", EXP);
        LocalDate d = EXP.minusDays(45);
        assertEquals(1, monitor.run(d).surfaced().size());
        assertEquals(0, monitor.run(d).surfaced().size());
        assertEquals(0, monitor.run(d).surfaced().size());
        assertEquals(3, store.runs().size());
    }

    @Test
    void dailyRunsSurfaceEachWindowExactlyOnce() {
        License l = lic("NY", "NY-1", EXP);
        for (LocalDate d = EXP.minusDays(120); !d.isAfter(EXP.plusDays(30)); d = d.plusDays(1)) {
            monitor.run(d);
        }
        assertEquals(List.of(EXPIRING_90, EXPIRING_60, EXPIRING_30, EXPIRED), surfacedWindows(l));
    }

    @Test
    void gapSurfacesCurrentWindowAndRecordsSkipped() {
        License l = lic("NY", "NY-1", EXP);
        monitor.run(EXP.minusDays(100));               // OK, nothing
        List<Surfacing> got = monitor.run(EXP.minusDays(20)).surfaced(); // down for 80 days
        assertEquals(1, got.size());
        assertEquals(EXPIRING_30, got.get(0).window());
        assertEquals(List.of(EXPIRING_90, EXPIRING_60), got.get(0).skippedWindows());
        assertEquals(List.of(EXPIRING_30), surfacedWindows(l));
    }

    @Test
    void gapAfterPartialSurfacingOnlySkipsWhatWasMissed() {
        License l = lic("NY", "NY-1", EXP);
        monitor.run(EXP.minusDays(80));                // EXPIRING_90
        Surfacing s = monitor.run(EXP.plusDays(3)).surfaced().get(0);
        assertEquals(EXPIRED, s.window());
        assertEquals(List.of(EXPIRING_60, EXPIRING_30), s.skippedWindows());
        assertEquals(List.of(EXPIRING_90, EXPIRED), surfacedWindows(l));
    }

    @Test
    void replayingAnEarlierDateDoesNotResurfaceLessSevereWindow() {
        License l = lic("NY", "NY-1", EXP);
        monitor.run(EXP.minusDays(10));                // EXPIRING_30
        assertEquals(0, monitor.run(EXP.minusDays(80)).surfaced().size());
        assertEquals(List.of(EXPIRING_30), surfacedWindows(l));
    }

    @Test
    void renewalStartsAFreshCycle() {
        License l = lic("NY", "NY-1", EXP);
        monitor.run(EXP.minusDays(10));
        LocalDate renewed = EXP.plusYears(2);
        store.putLicense(new License(l.npi(), l.licenseNumber(), l.licenseState(), renewed, CLOCK.instant()));
        assertEquals(0, monitor.run(EXP.minusDays(5)).surfaced().size()); // renewed: OK now
        List<Surfacing> got = monitor.run(renewed.minusDays(85)).surfaced();
        assertEquals(1, got.size());
        assertEquals(EXPIRING_90, got.get(0).window());
        assertEquals(renewed, got.get(0).licenseExpiration());
    }

    @Test
    void multipleLicensesForOneProviderTrackedIndependently() {
        License ny = lic("NY", "NY-1", EXP);
        License nj = lic("NJ", "NJ-1", EXP.plusDays(200));
        monitor.run(EXP.minusDays(50));
        assertEquals(List.of(EXPIRING_60), surfacedWindows(ny));
        assertEquals(List.of(), surfacedWindows(nj));
        monitor.run(EXP.plusDays(150));
        assertEquals(List.of(EXPIRING_60, EXPIRED), surfacedWindows(ny));
        assertEquals(List.of(EXPIRING_60), surfacedWindows(nj));
    }

    @Test
    void failedRunKeepsNothingAndIsRecorded() {
        lic("NY", "NY-1", EXP);
        // A null row in the middle of the scan simulates a mid-run failure.
        InMemoryStore failing = new InMemoryStore() {
            @Override
            public java.util.Collection<License> licenses() {
                List<License> ls = new ArrayList<>(store.licenses());
                ls.add(null);
                return ls;
            }
        };
        MonitorService.RunResult r = new MonitorService(failing, POLICY, CLOCK).run(EXP.minusDays(10));
        assertEquals(MonitorRun.Status.FAILED, r.run().status());
        assertTrue(failing.allSurfacings().isEmpty());
        assertEquals(1, failing.runs().size());
    }
}
