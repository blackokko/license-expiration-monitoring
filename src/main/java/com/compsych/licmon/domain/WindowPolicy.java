package com.compsych.licmon.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for "which window is this license in on this date".
 *
 * <p>Rule: {@code daysLeft = expiration - asOf}. The expiration day itself is still valid.
 * <ul>
 *   <li>daysLeft &lt; 0 &rarr; EXPIRED</li>
 *   <li>0..t30 &rarr; EXPIRING_30</li>
 *   <li>t30+1..t60 &rarr; EXPIRING_60</li>
 *   <li>t60+1..t90 &rarr; EXPIRING_90</li>
 *   <li>&gt; t90 &rarr; OK</li>
 * </ul>
 * Thresholds come from configuration; there are no threshold literals in the logic.
 */
public final class WindowPolicy {

    private final int t90;
    private final int t60;
    private final int t30;
    private final ZoneId zone;

    public WindowPolicy(int t90, int t60, int t30, ZoneId zone) {
        if (t30 < 0 || t60 <= t30 || t90 <= t60) {
            throw new IllegalArgumentException(
                    "thresholds must satisfy 0 <= EXPIRING_30 < EXPIRING_60 < EXPIRING_90, got "
                            + "EXPIRING_90=" + t90 + " EXPIRING_60=" + t60 + " EXPIRING_30=" + t30);
        }
        this.t90 = t90;
        this.t60 = t60;
        this.t30 = t30;
        this.zone = zone == null ? ZoneId.of("UTC") : zone;
    }

    public static WindowPolicy load(Path configFile) throws IOException {
        JsonNode root = new ObjectMapper().readTree(configFile.toFile());
        return fromJson(root);
    }

    public static WindowPolicy fromJson(JsonNode root) {
        JsonNode th = root.path("thresholds");
        for (String k : List.of("EXPIRING_90", "EXPIRING_60", "EXPIRING_30")) {
            if (!th.path(k).canConvertToInt()) {
                throw new IllegalArgumentException("config missing integer threshold " + k);
            }
        }
        ZoneId zone = root.hasNonNull("zone") ? ZoneId.of(root.get("zone").asText()) : ZoneId.of("UTC");
        return new WindowPolicy(th.get("EXPIRING_90").asInt(), th.get("EXPIRING_60").asInt(),
                th.get("EXPIRING_30").asInt(), zone);
    }

    public Window classify(LocalDate expiration, LocalDate asOf) {
        long daysLeft = ChronoUnit.DAYS.between(asOf, expiration);
        if (daysLeft < 0) return Window.EXPIRED;
        if (daysLeft <= t30) return Window.EXPIRING_30;
        if (daysLeft <= t60) return Window.EXPIRING_60;
        if (daysLeft <= t90) return Window.EXPIRING_90;
        return Window.OK;
    }

    /** First as-of date on which a license with this expiration is in {@code window}. */
    public LocalDate entryDate(Window window, LocalDate expiration) {
        return switch (window) {
            case OK -> expiration.minusDays(t90 + 1L);
            case EXPIRING_90 -> expiration.minusDays(t90);
            case EXPIRING_60 -> expiration.minusDays(t60);
            case EXPIRING_30 -> expiration.minusDays(t30);
            case EXPIRED -> expiration.plusDays(1);
        };
    }

    /** Alerting windows strictly less severe than {@code current}, in severity order. */
    public static List<Window> alertingWindowsBefore(Window current) {
        List<Window> out = new ArrayList<>();
        for (Window w : Window.values()) {
            if (w.isAlerting() && current.moreSevereThan(w)) out.add(w);
        }
        return out;
    }

    public Map<Window, Integer> thresholds() {
        Map<Window, Integer> m = new EnumMap<>(Window.class);
        m.put(Window.EXPIRING_90, t90);
        m.put(Window.EXPIRING_60, t60);
        m.put(Window.EXPIRING_30, t30);
        return m;
    }

    /** Stable identifier recorded on every surfacing, so we can tell which policy produced it. */
    public String version() {
        return "90=" + t90 + ",60=" + t60 + ",30=" + t30;
    }

    public ZoneId zone() {
        return zone;
    }
}
