# Walkthrough notes

## How to run

```
.\mvnw.cmd test                      # 50 tests
.\mvnw.cmd -q package -DskipTests    # builds target/licmon.jar
java -jar target/licmon.jar ingest data.csv
java -jar target/licmon.jar run    --as-of 2026-11-24
java -jar target/licmon.jar query  --as-of 2026-11-24 --window EXPIRING_60
java -jar target/licmon.jar status                # exit 4 if unhealthy
java -jar target/licmon.jar demo NY NY-44821     # requirement 5, on a scratch copy
```

State persists in `data/state.json` (override with `--state FILE`); thresholds in `config/windows.json`.

## Schema (as it would be on Postgres)

- `provider(npi char(10) PK, first_name, last_name, created_at, updated_at)`
- `license(id PK, npi FK, license_state char(2), license_number, license_expiration date, expiration_known_since date, updated_at, UNIQUE(license_state, license_number))`
- `surfacing(id PK, license_id FK, window, license_expiration, as_of, skipped_windows, passed_before_known, policy_version, run_id FK, surfaced_at, UNIQUE(license_id, window, license_expiration))`
- `monitor_run(run_id PK, as_of, started_at, finished_at, status, licenses_evaluated, surfaced_count, error)`

## Design points

- **Window is derived, never stored.** It's computed from expiration date + as-of date + thresholds config. The only stored fact is the ledger: "window W was surfaced for this license at this expiration date."
- **Nothing is missed.** Every run evaluates all ~55k licenses against the as-of date, not just changes since the last run, so downtime can't let a license slip past. After a gap, the current window is surfaced once and the jumped-over windows are recorded.
- **Real gaps vs. late knowledge.** Each license stores `expirationKnownSince`, the as-of date we first held its current expiration date (set on insert and when the date changes). Jumped-over windows are split: `skippedWindows` = windows the license was in while we knew the date (a missed run, a real monitoring gap); `passedBeforeKnown` = windows it had left before we learned the date (loaded late, or the date was corrected). A license loaded already expired shows no skipped windows.
- **No repeats.** A window is surfaced only if it is more severe than anything already surfaced for that license + expiration date. The expiration date is part of the key, so a renewal starts a fresh cycle; replaying an earlier date can't bring back a less severe window.
- **Runs are all-or-nothing.** A run's surfacings and its run record are committed together; the state file is written to a temp file and atomically swapped in, so a crash leaves the previous good file. A failed run records only that it failed.
- **One policy, held in config, not in the database.** There is a single window policy, the thresholds in `config/windows.json` (validated 0 <= 30 < 60 < 90), so there's no policy table. A 120/45/15 test proves they change without code changes. Each surfacing keeps `policy_version` (the threshold values, e.g. `90=90,60=60,30=30`) as a plain column, so when compliance changes the numbers you can still tell which thresholds produced an old alert.
- **Boundary rule (one place, `WindowPolicy.classify`).** `daysLeft = expiration - asOf`; EXPIRED if < 0; EXPIRING_30 0..30; EXPIRING_60 31..60; EXPIRING_90 61..90; OK > 90. The expiration day itself is still valid.
- **Time is injected.** All logic takes a `LocalDate asOf`; "today" comes from an injected `Clock` in the configured zone (America/Chicago). `--as-of` is how time moves for tests and the demo; the system clock is never touched.
- **Observability (`HealthCheck`).** `status` judges health against the real today from the injected `Clock`, never against `--as-of` (which only moves the window counts). Unhealthy if: no successful run; latest successful run for today-or-earlier is more than 1 day old; or any successful run is dated after today. Each problem is listed in `problems`; a failed latest run is a `warning`. Exit code 4 when unhealthy, so cron/monitoring can alert on it.
- **Future-dated runs are refused.** `run --as-of` after today exits 5 without touching state: a future run's surfacings would sit in the ledger and suppress the real alerts when those dates arrive. `--allow-future` overrides, intended only with a scratch `--state` file; `demo` never touches real state.
- **Intake.** `ingest --as-of D` stamps D as the date the data is known. Bad rows are reported with line numbers and skipped, never abort the file. Licenses upsert on (state, number). A license number claimed by a different NPI is rejected rather than silently reassigned; name changes produce a warning.

## Things to raise before trusting it

1. **Thresholds can change mid-cycle.** If compliance changes 90 to 120, a license already surfaced for EXPIRING_90 isn't surfaced again. Defensible, but someone should decide it.
2. **Data-format guesses.** Slash dates are read as US month/day/year; the key is state + license number. Check both against the real dataset.
3. **The Maven download isn't verified.** `mvnw.cmd` is a minimal hand-written bootstrap with no checksum check.
4. **Single writer assumed.** Two runs at once would each load and save the whole file; the last one wins. Postgres would use a transaction plus an advisory lock.
5. **Stale data (out of scope by decision).** The expiration date we hold may no longer be the truth (renewed, revoked). Production would want a `last_verified_at` and a flag for licenses past expiry that were never re-verified.
6. **No recovery for a ledger already poisoned by a future run.** `status` detects it and `run` now prevents it, but there's no `revert-run <id>` to remove that run's surfacings. In Postgres it's a `DELETE ... WHERE run_id = ?`.
7. **Notifications would change the design.** Delivering alerts reliably needs an outbox table written in the same transaction as the ledger, so a crash can't lose or double-send an alert.

## Not yet implemented

- `schema.sql` for the Postgres design (only documented above). Foreign keys (license -> provider, surfacing -> license / monitor_run) are therefore not enforced in code; `putLicense` doesn't check the provider exists.
- A scheduler. `run` is invoked manually; production would call it daily via cron or a job scheduler.
- `revert-run <id>` (see item 6).

## Going forward: fixes and improvements

Verified so far: all five requirements work on the 17-row interview dataset (boundaries 91/90/60/30/0/-1 days classify correctly at 2027-03-01; a 97-day gap surfaces once with the right skipped windows; the demo surfaces exactly once per step and zero on re-run). A mutation pass showed where the tests are thin.

### Correctness fixes (small, do first)
1. **Locale-sensitive uppercasing.** `License.keyOf` and `query --window` call `toUpperCase()` with the default locale; under a Turkish locale `"il"` becomes `"İL"`, splitting one license into two keys. Use `toUpperCase(Locale.ROOT)` and add a test under `tr-TR`.
2. **FK checks.** Reject a license whose NPI has no provider (or create the provider in the same step) so the in-memory store matches the Postgres constraints.
3. **NPI check digit.** NPI is validated as 10 digits only. 14 of 17 interview NPIs fail the Luhn check, so if added it must be a warning, not a rejection.

### Test gaps (mutations that survived)
4. **Requirement 1 (query) and requirement 5 (demo) have no direct tests.** Add `QueryService` tests (every window present, OK licenses listed, boundary day) and a `demo` CLI test asserting one surfacing per step and none on re-run.
5. **Store unique constraint** (the ledger backstop for no-repeats) and that it survives reload: add tests that a duplicate surfacing is rejected before and after a snapshot round-trip.
6. **Custom thresholds through the monitor,** not just through `WindowPolicy`: run 120/45/15 end to end.
7. **knownSince snapshot round-trip** and the 9-digit NPI boundary.
8. **CLI tests use the real `config/windows.json`.** Give them a temp `--config` so a config edit can't break them.
9. **Brittle assertions.** Some tests match JSON substrings or reuse `entryDate()` to compute expectations (tautological). Parse the JSON and hard-code expected dates; add a shared License test builder.

### Requirement-level improvements
10. **Time zones / DST.** Today is a single configured zone (America/Chicago) and all logic uses `LocalDate`, so DST can't shift a window. To standardize across US zones: store instants in UTC, keep `LocalDate` for expiration, and decide "today" either per license (zone from `license_state`, with a per-license override for multi-zone states) or as one company-wide zone. Add DST-transition tests and a `WindowPolicy.today(Clock)` helper so `Main` isn't the only place that resolves today.
11. **Threshold changes mid-cycle (item 1).** Decide the rule, then encode it: either re-surface under the new policy (key the ledger on `policy_version` too) or keep the current "most severe already surfaced" rule. `policy_version` is already stored so either is possible.
12. **Ledger recovery.** `revert-run <id>` to delete one run's surfacings; with Postgres, `DELETE FROM surfacing WHERE run_id = ?` in a transaction.
13. **Concurrency.** Single writer is assumed. Add a lock file now; Postgres would use a transaction plus `pg_advisory_lock`.
14. **Notifications.** An outbox table written in the same transaction as the ledger, delivered at-least-once with an idempotency key of (license, window, expiration).
15. **Stale data (out of scope by decision).** `last_verified_at` plus a flag for licenses past expiry that were never re-verified.

### Process
16. **Integration tests** against a real Postgres (Testcontainers) once `schema.sql` exists, so the constraints are tested where they live.
17. **Regression tests** if this lands on an existing feature: pin current outputs for a fixed dataset and diff on every change.
18. **Independent review** by a model outside the one that wrote the code, plus re-running the mutation harness after each change.
19. **Pin the Maven download** with a checksum in `mvnw.cmd` (item 3).

## Scope decisions

- Stale-data handling is out of scope; discussion only (item 5).
- Scale is taken as given in the prompt (~55k licenses, growing slowly): full scans and a whole-file JSON snapshot are deliberate, not oversights. The `Store` interface is the seam for Postgres if that changes.
