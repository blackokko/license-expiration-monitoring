package com.compsych.licmon.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Ledger entry: "window W was surfaced for this license, at this expiration date".
 * Unique on (licenseKey, window, licenseExpiration). Including the expiration in the key means a
 * renewal (new date) starts a fresh cycle instead of being suppressed by the old one.
 *
 * @param skippedWindows    alerting windows the license was in while we knew its expiration date
 *                          but that were never surfaced (the monitor didn't run): a real gap.
 * @param passedBeforeKnown alerting windows the license had already left before we learned its
 *                          expiration date (loaded late, or date corrected): not a monitoring gap.
 */
public record Surfacing(String licenseKey, String npi, String licenseNumber, String licenseState,
                        Window window, LocalDate licenseExpiration, LocalDate asOf,
                        List<Window> skippedWindows, List<Window> passedBeforeKnown,
                        String policyVersion, String runId, Instant surfacedAt) {
}
