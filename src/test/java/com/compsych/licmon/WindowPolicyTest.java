package com.compsych.licmon;

import com.compsych.licmon.domain.Window;
import com.compsych.licmon.domain.WindowPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowPolicyTest {

    static final WindowPolicy DEFAULT = new WindowPolicy(90, 60, 30, ZoneId.of("UTC"));
    static final LocalDate EXP = LocalDate.of(2027, 5, 30);

    @ParameterizedTest(name = "{0} days left -> {1}")
    @CsvSource({
            "-365, EXPIRED", "-1, EXPIRED",
            "0, EXPIRING_30", "1, EXPIRING_30", "30, EXPIRING_30",
            "31, EXPIRING_60", "60, EXPIRING_60",
            "61, EXPIRING_90", "90, EXPIRING_90",
            "91, OK", "1000, OK"})
    void boundaries(int daysLeft, Window expected) {
        assertEquals(expected, DEFAULT.classify(EXP, EXP.minusDays(daysLeft)));
    }

    @Test
    void entryDatesAreTheFirstDayOfEachWindow() {
        for (Window w : Window.values()) {
            LocalDate d = DEFAULT.entryDate(w, EXP);
            assertEquals(w, DEFAULT.classify(EXP, d), "entry date for " + w);
            if (w != Window.OK) {
                assertEquals(Window.values()[w.ordinal() - 1], DEFAULT.classify(EXP, d.minusDays(1)),
                        "day before entry into " + w);
            }
        }
    }

    @Test
    void customThresholdsReclassifyWithoutCodeChange() throws Exception {
        WindowPolicy p = WindowPolicy.fromJson(new ObjectMapper().readTree(
                "{\"thresholds\":{\"EXPIRING_90\":120,\"EXPIRING_60\":45,\"EXPIRING_30\":15}}"));
        assertEquals(Window.EXPIRING_90, p.classify(EXP, EXP.minusDays(100)));
        assertEquals(Window.EXPIRING_90, p.classify(EXP, EXP.minusDays(46)));
        assertEquals(Window.EXPIRING_60, p.classify(EXP, EXP.minusDays(45)));
        assertEquals(Window.EXPIRING_30, p.classify(EXP, EXP.minusDays(15)));
        assertEquals(Window.OK, p.classify(EXP, EXP.minusDays(121)));
    }

    @Test
    void shippedConfigLoads() throws Exception {
        WindowPolicy p = WindowPolicy.load(Path.of("config/windows.json"));
        assertEquals("90=90,60=60,30=30", p.version());
        assertEquals(ZoneId.of("America/Chicago"), p.zone());
    }

    @Test
    void rejectsBadThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(60, 90, 30, null));
        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(90, 60, 60, null));
        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(90, 60, -1, null));
        assertThrows(IllegalArgumentException.class, () -> WindowPolicy.fromJson(
                new ObjectMapper().readTree("{\"thresholds\":{\"EXPIRING_90\":90}}")));
    }
}
