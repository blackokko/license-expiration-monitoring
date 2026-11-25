package com.compsych.licmon;

import com.compsych.licmon.domain.License;
import com.compsych.licmon.domain.LicenseView;
import com.compsych.licmon.domain.Provider;
import com.compsych.licmon.domain.Window;
import com.compsych.licmon.domain.WindowPolicy;
import com.compsych.licmon.service.MonitorService;
import com.compsych.licmon.store.InMemoryStore;
import com.compsych.licmon.store.SnapshotFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapshotFileTest {

    static final WindowPolicy POLICY = new WindowPolicy(90, 60, 30, ZoneId.of("UTC"));
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void ledgerSurvivesRestartSoNoRepeats(@TempDir Path dir) throws Exception {
        SnapshotFile file = new SnapshotFile(dir.resolve("state.json"));
        LocalDate exp = LocalDate.of(2027, 5, 30);

        InMemoryStore s1 = file.load();
        s1.putProvider(new Provider("1234567890", "Ann", "Lee", CLOCK.instant()));
        s1.putLicense(new License("1234567890", "NY-44821", "NY", exp, exp.minusDays(365), CLOCK.instant()));
        assertEquals(1, new MonitorService(s1, POLICY, CLOCK).run(exp.minusDays(70)).surfaced().size());
        file.save(s1);

        InMemoryStore s2 = file.load();
        assertEquals(1, s2.licenses().size());
        assertEquals(1, s2.allSurfacings().size());
        assertEquals(1, s2.runs().size());
        assertEquals("Ann", s2.provider("1234567890").orElseThrow().firstName());
        assertEquals(0, new MonitorService(s2, POLICY, CLOCK).run(exp.minusDays(70)).surfaced().size());
        assertEquals(1, new MonitorService(s2, POLICY, CLOCK).run(exp.minusDays(50)).surfaced().size());
    }

    @Test
    void licenseViewMatchesPrescribedJson() throws Exception {
        License l = new License("1234567890", "NY-44821", "NY", LocalDate.of(2027, 5, 30), LocalDate.of(2026, 11, 24), CLOCK.instant());
        String json = Json.MAPPER.writeValueAsString(LicenseView.of(l, Window.EXPIRING_90));
        assertEquals("{\"npi\":\"1234567890\",\"license_number\":\"NY-44821\",\"license_state\":\"NY\","
                + "\"license_expiration\":\"2027-05-30\",\"window\":\"EXPIRING_90\"}", json);
    }
}
