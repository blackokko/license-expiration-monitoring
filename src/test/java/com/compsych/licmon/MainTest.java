package com.compsych.licmon;

import com.compsych.licmon.cli.Main;
import com.compsych.licmon.store.SnapshotFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI-level behavior. Real "today" is pinned to 2026-11-24 (America/Chicago) via the Clock. */
class MainTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T18:00:00Z"), ZoneOffset.UTC);

    Clock clock = CLOCK;

    @TempDir
    Path dir;
    Path state;
    ByteArrayOutputStream buf;

    @BeforeEach
    void setUp() throws Exception {
        state = dir.resolve("state.json");
        Path csv = dir.resolve("one.csv");
        Files.writeString(csv, "npi,first_name,last_name,license_number,license_state,license_expiration\n"
                + "1234567890,Ann,Lee,NY-44821,NY,2027-05-30\n");
        assertEquals(0, cli("ingest", csv.toString()));
    }

    int cli(String... args) {
        buf = new ByteArrayOutputStream();
        String[] full = new String[args.length + 2];
        full[0] = "--state";
        full[1] = state.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return new Main(new PrintStream(buf, true, StandardCharsets.UTF_8), clock).execute(full);
    }

    String output() {
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void futureRunRefusedAndStateUntouched() throws Exception {
        assertEquals(5, cli("run", "--as-of", "2027-06-15"));
        assertTrue(output().contains("refusing"));
        assertEquals(0, new SnapshotFile(state).load().runs().size());
        assertEquals(0, new SnapshotFile(state).load().allSurfacings().size());
    }

    @Test
    void realAlertFiresWhenItsDateActuallyArrives() {
        assertEquals(5, cli("run", "--as-of", "2027-06-15"));   // rehearsal attempt: refused
        clock = Clock.fixed(Instant.parse("2027-03-01T18:00:00Z"), ZoneOffset.UTC); // time really passes
        assertEquals(0, cli("run"));
        // Before the fix, the future run's EXPIRED surfacing suppressed this.
        assertTrue(output().contains("\"window\":\"EXPIRING_90\""));
        assertTrue(output().contains("\"surfacedCount\":1"));
    }

    @Test
    void allowFutureOverridesAndStatusThenReportsUnhealthy() {
        assertEquals(0, cli("run"));
        assertEquals(0, cli("status"));
        assertTrue(output().contains("\"healthy\":true"));

        assertEquals(0, cli("run", "--as-of", "2027-06-15", "--allow-future"));
        assertEquals(4, cli("status"));
        assertTrue(output().contains("\"healthy\":false"));
        assertTrue(output().contains("\"future_dated_runs\":[\"2027-06-15\"]"));
        assertTrue(output().contains("\"days_since_last_successful_run\":0"));
    }

    @Test
    void statusAsOfMovesCountsButNotHealth() {
        assertEquals(0, cli("run"));
        assertEquals(0, cli("status", "--as-of", "2030-01-01"));   // healthy: judged against real today
        assertTrue(output().contains("\"counts_as_of\":\"2030-01-01\""));
        assertTrue(output().contains("\"EXPIRED\":1"));
    }

    @Test
    void neverRunIsUnhealthy() {
        assertEquals(4, cli("status"));
        assertTrue(output().contains("no successful run"));
    }
}
