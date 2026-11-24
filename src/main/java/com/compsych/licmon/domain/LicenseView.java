package com.compsych.licmon.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDate;

/** The prescribed output shape: a license and its window on a given date. */
@JsonPropertyOrder({"npi", "license_number", "license_state", "license_expiration", "window"})
public record LicenseView(
        @JsonProperty("npi") String npi,
        @JsonProperty("license_number") String licenseNumber,
        @JsonProperty("license_state") String licenseState,
        @JsonProperty("license_expiration") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate licenseExpiration,
        @JsonProperty("window") Window window) {

    public static LicenseView of(License l, Window w) {
        return new LicenseView(l.npi(), l.licenseNumber(), l.licenseState(), l.licenseExpiration(), w);
    }
}
