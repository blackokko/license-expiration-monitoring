# Walkthrough notes

## How to run

```
.\mvnw.cmd test                      # 30 tests
.\mvnw.cmd -q package -DskipTests    # builds target/licmon.jar
java -jar target/licmon.jar ingest data.csv
java -jar target/licmon.jar run    --as-of 2026-11-24
java -jar target/licmon.jar query  --as-of 2026-11-24 --window EXPIRING_60
java -jar target/licmon.jar status --as-of 2026-11-29
java -jar target/licmon.jar demo NY NY-44821     # requirement 5, on a scratch copy
```

State persists in `data/state.json` (override with `--state FILE`); thresholds in `config/windows.json`.

## Schema (as it would be on Postgres)

- `provider(npi char(10) PK, first_name, last_name, created_at, updated_at)`
- `license(id PK, npi FK, license_state char(2), license_number, license_expiration date, updated_at, UNIQUE(license_state, license_number))`
- `surfacing(id PK, license_id FK, window, license_expiration, as_of, skipped_windows, policy_version, run_id FK, surfaced_at, UNIQUE(license_id, window, license_expiration))`
- `monitor_run(run_id PK, as_of, started_at, finished_at, status, licenses_evaluated, surfaced_count, error)`
- `window_policy(version PK, thresholds, effective_from)` (production story; runtime reads the config file)

## Design points

- **Window is derived, never stored.** It's computed from expiration date + as-of date + thresholds config. The only stored fact is the ledger: "window W was surfaced for this license at this expiration date."
- **Nothing is missed.** Every run evaluates all ~55k licenses against the as-of date, not just changes since the last run, so downtime can't let a license slip past. After a gap, the current window is surfaced once and any jumped-over windows are recorded as `skippedWindows`.
- **No repeats.** A window is surfaced only if it is more severe than anything already surfaced for that license + expiration date. The expiration date is part of the key, so a renewal starts a fresh cycle; replaying an earlier date can't bring back a less severe window.
- **Runs are all-or-nothing.** A run's surfacings and its run record are committed together; the state file is written to a temp file and atomically swapped in, so a crash leaves the previous good file. A failed run records only that it failed.
- **Thresholds are configuration.** `config/windows.json`, validated (0 <= 30 < 60 < 90). A 120/45/15 test proves they change without code changes. Each surfacing records the policy version that produced it.
- **Boundary rule (one place, `WindowPolicy.classify`).** `daysLeft = expiration - asOf`; EXPIRED if < 0; EXPIRING_30 0..30; EXPIRING_60 31..60; EXPIRING_90 61..90; OK > 90. The expiration day itself is still valid.
- **Time is injected.** All logic takes a `LocalDate asOf`; "today" comes from an injected `Clock` in the configured zone (America/Chicago). `--as-of` is how time moves for tests and the demo; the system clock is never touched.
- **Observability.** `status` reports the last run, days since the last successful run, `healthy` (false when more than 1 day behind), and counts per window.
- **Intake.** Bad rows are reported with line numbers and skipped, never abort the file. Licenses upsert on (state, number). A license number claimed by a different NPI is rejected rather than silently reassigned; name changes produce a warning.

## Things to raise before trusting it

1. **False "skipped windows" on the first run after loading data.** A license that was already expired when loaded shows 90/60/30 as skipped, but those days passed before the system knew about it. It should say "already in this window when loaded" instead.
2. **Thresholds can change mid-cycle.** If compliance changes 90 to 120, a license already surfaced for EXPIRING_90 isn't surfaced again. Defensible, but someone should decide it.
3. **Data-format guesses.** Slash dates are read as US month/day/year; the key is state + license number. Check both against the real dataset.
4. **The Maven download isn't verified.** `mvnw.cmd` is a minimal hand-written bootstrap with no checksum check.
5. **Single writer assumed.** Two runs at once would each load and save the whole file; the last one wins. Postgres would use a transaction plus an advisory lock.
6. **Stale data.** The expiration date we hold may no longer be the truth (renewed, revoked). Production wants a `last_verified_at` and a flag for licenses past expiry that were never re-verified.
7. **Notifications would change the design.** Delivering alerts reliably needs an outbox table written in the same transaction as the ledger, so a crash can't lose or double-send an alert.

## Not yet implemented

- Fix for item 1 above.
- Ingest against the real interview dataset (parser may need adjusting).
- `schema.sql` for the Postgres design (only documented above).
- A scheduler. `run` is invoked manually; production would call it daily via cron or a job scheduler.
- Stale-data handling (item 6), beyond discussion.
