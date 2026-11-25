package com.compsych.licmon.service;

import com.compsych.licmon.domain.License;
import com.compsych.licmon.domain.Provider;
import com.compsych.licmon.store.Store;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads intake rows (CSV with a header row). Bad rows are reported and skipped, never abort the
 * file. Licenses are upserted on (state, number); providers on NPI.
 */
public final class IntakeService {

    static final List<String> COLUMNS = List.of(
            "npi", "first_name", "last_name", "license_number", "license_state", "license_expiration");

    private static final Pattern NPI = Pattern.compile("\\d{10}");
    private static final Set<String> STATES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS",
            "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY",
            "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV",
            "WI", "WY", "DC", "PR", "VI", "GU", "AS", "MP");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT));

    private final Store store;
    private final Clock clock;

    public IntakeService(Store store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public record RowIssue(int line, String message) {
    }

    public record Result(int inserted, int updated, int unchanged, List<RowIssue> errors, List<RowIssue> warnings) {
    }

    /**
     * @param asOf the business date of this intake. Recorded as {@code expirationKnownSince} on new
     *             licenses and on licenses whose expiration date changed.
     */
    public Result ingestCsv(Reader in, LocalDate asOf) throws IOException {
        BufferedReader r = new BufferedReader(in);
        String header = r.readLine();
        if (header == null) throw new IllegalArgumentException("empty file");
        if (header.startsWith("﻿")) header = header.substring(1);
        Map<String, Integer> idx = new HashMap<>();
        List<String> cols = Csv.split(header);
        for (int i = 0; i < cols.size(); i++) idx.put(cols.get(i).trim().toLowerCase(Locale.ROOT), i);
        for (String c : COLUMNS) {
            if (!idx.containsKey(c)) throw new IllegalArgumentException("missing column: " + c);
        }

        int inserted = 0, updated = 0, unchanged = 0;
        List<RowIssue> errors = new ArrayList<>();
        List<RowIssue> warnings = new ArrayList<>();
        Map<String, Integer> seenInFile = new HashMap<>();
        Instant now = clock.instant();

        String line;
        int lineNo = 1;
        while ((line = r.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) continue;
            List<String> f = Csv.split(line);
            String npi = get(f, idx, "npi");
            String first = get(f, idx, "first_name");
            String last = get(f, idx, "last_name");
            String number = get(f, idx, "license_number");
            String state = get(f, idx, "license_state").toUpperCase(Locale.ROOT);
            String expRaw = get(f, idx, "license_expiration");

            List<String> problems = new ArrayList<>();
            if (!NPI.matcher(npi).matches()) problems.add("npi must be 10 digits: '" + npi + "'");
            if (number.isEmpty()) problems.add("license_number is blank");
            if (!STATES.contains(state)) problems.add("license_state not a US state code: '" + state + "'");
            Optional<LocalDate> exp = parseDate(expRaw);
            if (exp.isEmpty()) problems.add("license_expiration not a valid date: '" + expRaw + "'");
            if (!problems.isEmpty()) {
                errors.add(new RowIssue(lineNo, String.join("; ", problems)));
                continue;
            }

            String key = License.keyOf(state, number);
            Integer prevLine = seenInFile.put(key, lineNo);
            if (prevLine != null) {
                warnings.add(new RowIssue(lineNo, "license " + key + " also on line " + prevLine + "; later row wins"));
            }

            Optional<License> existing = store.license(key);
            if (existing.isPresent() && !existing.get().npi().equals(npi)) {
                // Same license number claimed by a different provider: don't silently reassign it.
                errors.add(new RowIssue(lineNo, "license " + key + " belongs to npi " + existing.get().npi()
                        + ", row says " + npi));
                continue;
            }

            Optional<Provider> p = store.provider(npi);
            if (p.isPresent() && (!p.get().firstName().equals(first) || !p.get().lastName().equals(last))) {
                warnings.add(new RowIssue(lineNo, "npi " + npi + " name changed from "
                        + p.get().firstName() + " " + p.get().lastName() + " to " + first + " " + last));
            }
            if (p.isEmpty() || !p.get().firstName().equals(first) || !p.get().lastName().equals(last)) {
                store.putProvider(new Provider(npi, first, last, now));
            }

            if (existing.isEmpty()) {
                inserted++;
            } else if (existing.get().licenseExpiration().equals(exp.get())) {
                unchanged++;
                continue;
            } else {
                updated++;
            }
            store.putLicense(new License(npi, number.trim(), state, exp.get(), asOf, now));
        }
        return new Result(inserted, updated, unchanged, errors, warnings);
    }

    private static String get(List<String> f, Map<String, Integer> idx, String col) {
        int i = idx.get(col);
        return i < f.size() ? f.get(i).trim() : "";
    }

    static Optional<LocalDate> parseDate(String s) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(s, fmt));
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return Optional.empty();
    }
}
