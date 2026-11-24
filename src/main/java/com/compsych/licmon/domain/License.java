package com.compsych.licmon.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One state license. Natural key is (state, number): a provider (NPI) can hold many licenses,
 * and each is tracked on its own.
 */
public record License(String npi, String licenseNumber, String licenseState,
                      LocalDate licenseExpiration, Instant updatedAt) {

    @JsonIgnore
    public String key() {
        return keyOf(licenseState, licenseNumber);
    }

    public static String keyOf(String state, String number) {
        return state.trim().toUpperCase() + "|" + number.trim();
    }
}
