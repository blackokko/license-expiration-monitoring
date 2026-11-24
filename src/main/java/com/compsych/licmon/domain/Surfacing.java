package com.compsych.licmon.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Ledger entry: "window W was surfaced for this license, at this expiration date".
 * Unique on (licenseKey, window, licenseExpiration). Including the expiration in the key means a
 * renewal (new date) starts a fresh cycle instead of being suppressed by the old one.
 *
 * @param skippedWindows alerting windows the license passed through without ever being surfaced
 *                       (e.g. the monitor didn't run), so the gap is visible rather than silent.
 */
public record Surfacing(String licenseKey, String npi, String licenseNumber, String licenseState,
                        Window window, LocalDate licenseExpiration, LocalDate asOf,
                        List<Window> skippedWindows, String policyVersion, String runId,
                        Instant surfacedAt) {
}
