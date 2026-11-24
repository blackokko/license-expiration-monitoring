package com.compsych.licmon.domain;

/**
 * Expiration windows, declared in order of increasing severity. Ordinal order is relied on:
 * a license only ever moves forward through this list as time passes (for a fixed expiration date).
 */
public enum Window {
    OK,
    EXPIRING_90,
    EXPIRING_60,
    EXPIRING_30,
    EXPIRED;

    /** Windows that are surfaced (everything except OK). */
    public boolean isAlerting() {
        return this != OK;
    }

    public boolean moreSevereThan(Window other) {
        return this.ordinal() > other.ordinal();
    }
}
