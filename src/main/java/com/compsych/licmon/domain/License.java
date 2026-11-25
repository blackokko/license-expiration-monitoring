package com.compsych.licmon.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One state license. Natural key is (state, number): a provider (NPI) can hold many licenses,
 * and each is tracked on its own.
 *
 * @param expirationKnownSince the as-of date on which we first held this expiration date (set on
 *                             insert and whenever the date changes). Windows the license had already
 *                             left before this date were never ours to surface, so they are not
 *                             "skipped". Null only in snapshots written before the field existed.
 */
public record License(String npi, String licenseNumber, String licenseState,
                      LocalDate licenseExpiration, LocalDate expirationKnownSince, Instant updatedAt) {
    @JsonIgnore
    public String key() {
        return keyOf(licenseState, licenseNumber);
    }

    public static String keyOf(String state, String number) {
        return state.trim().toUpperCase() + "|" + number.trim();
    }
}
