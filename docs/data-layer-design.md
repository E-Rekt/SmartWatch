# Data layer design — step 2 (as built)

Decided 2026-07-13 by a 3-design panel (lenses: minimum-viable, reminder-reliability,
low-RAM) with two independent judges; both ranked **mvp first** and prescribed
specific grafts from the others. This document records the synthesis as
implemented in `CZTask/`.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Layout | Standalone Gradle project `CZTask/`, sibling of `CZProbe/`, single `:app` module | Repo root stays docs; byte-identical deploy loop to the proven probe; no wrapper/version drift |
| Stack | Room 2.6.1 + KSP 1.9.24-1.0.20 + coroutines 1.8.1 on Kotlin 1.9.24 / AGP 8.5.2 / Gradle 8.7 | Room 2.6.1 is the **terminal** Room for Kotlin 1.9 — do not upgrade Room without upgrading Kotlin |
| Schema | 3 tables (`task`, `reminder`, `timer_preset`), explicit snake_case columns, zero TypeConverters | Snake_case survives Kotlin renames; primitives keep schema JSON readable |
| Repeat rule | `time_of_day_minutes` + `days_of_week_mask` (bit0=Mon…bit6=Sun; 0 = one-shot with `date_epoch_day`) | One Int covers one-shot/daily/weekly/day-sets; no RRULE |
| Rule time base | **Local civil time**; UTC instants computed transiently at scheduling time | "08:00 daily" must survive DST, zone changes, and clock corrections |
| Next-fire | **Derived, never stored** (`NextFireCalculator`, pure) | Deletes cache invalidation — the exact failure mode of a clock-jumping device; microseconds at <50 rows |
| Backward-jump dedup | `last_fired_occurrence_utc_millis` per reminder (scheduled instant, not delivery time) | "09:00 daily" cannot double-fire when NTP steps the clock back past 09:00; doubles as one-shot completion state |
| Late fires | One-shots: unbounded (better late than never). Repeating: **≤30 min grace**, latest missed occurrence only | Judge-mandated fix — reboot at 08:58 still delivers the 09:00 pill reminder; never a catch-up storm |
| Done-task semantics | `enabledOnce()` joins `task.done`; `setDone()` returns affected reminder ids | Judge-mandated — a checked-off "take out trash" must not fire that evening |
| Timers | `timer_preset` only; running state in SharedPreferences (`TimerStateStore`: elapsed-axis end + boot count) | Measured 30–60 s process kills make service memory unsafe; SP is readable pre-Room in a receiver; reboot cancels a timer (bootCount mismatch), reported once |
| Clock sanity | `ClockGuard`: build-time floor (day-quantized `BuildConfig`), persisted high-water mark, wall-vs-elapsed drift detector (boot-count scoped), read-only `auto_time` check | Detects the lived 15-month incident class; app cannot set the clock — banner is the ceiling |
| DB opening | WAL + `synchronous=NORMAL`; pre-open `.bak` file copy; **no** destructive-migration fallback ever | WAL amortizes fsync on slow eMMC; the backup is the escape hatch if a future migration fails |
| Escape hatch | `JsonDump` (pure Kotlin) renders all tables to JSON | `allowBackup=false` + one SQLite file on a 5-year-old watch needs a way out |
| IDs contract | `contract/Ids.kt`: singletons 1..99, reminders 1000+id | Collisions are silent; pinned before step 4 exists |
| DI | Manual `ServiceLocator` | Hilt is dex weight + startup cost for zero benefit at this scale |
| Voice capture prep | `task.source` INTEGER (0=manual, 1=voice) | One column now removes the only plausible step-5 migration |

## The step-4 contract

1. `ReminderRepository.schedulePlan(now)` is idempotent and total: returns
   `dueNow` (as (reminder, occurrence) pairs — fire these, then `markFired`
   with the **scheduled** instant), plus one coalesced next-fire instant and
   its reminder ids. Call it from every entry point and after every mutation.
2. `TaskRepository.delete/clearDone/setDone` return affected reminder ids
   *before* the evidence is destroyed — cancel notifications/alarms with them.
3. Timer state: `TimerStateStore.save/recover(bootCount)/clear`. `recover`
   returns a sealed `Recovery`: `Running(timer)`, `None`, or — after a reboot —
   `LostToReboot(timer)` exactly once (self-clears), so step 4 can show a
   "timer lost to restart" note (policy: reboot cancels a timer).
4. Notification/request-code allocation is `contract/Ids.kt`, nowhere else.

## Broadcast delivery — measured on this watch (Wear OS 2.66, Fossil build)

Tested empirically with a debug-only manifest receiver, app process killed:

| Broadcast | Delivered to dead process? | Consequence for step 4 |
|---|---|---|
| `TIMEZONE_CHANGED` | **YES** (process spawned, receiver ran) | Manifest receiver works |
| `BOOT_COMPLETED` | **YES** (measured via real reboot; receiver wrote proof to disk during boot) | Manifest receiver + `RECEIVE_BOOT_COMPLETED` permission works — boot re-planning is safe to build on |
| `TIME_SET` | **NO for programmatic clock changes** (`cmd alarm set-time`, tested twice) — but **YES once at boot**, 15 s after BOOT_COMPLETED, when auto_time's NTP corrected the clock | Boot-time corrections are covered by the BOOT_COMPLETED re-plan anyway. Mid-session clock changes need a **runtime-registered** receiver (while any component lives) **plus a daily elapsed-axis reconcile alarm** as the backstop. RTC alarms themselves shift with the wall clock, so the armed next-fire self-corrects; the reconcile closes the rest. |

Reboot side-findings: the watch clock self-heals at boot via auto_time+Wi-Fi
(the incident class can no longer silently persist across reboots), and Wi-Fi
ADB survives reboot — but run `adb disconnect` before reconnecting, or the
stale cached connection blocks the retry.

The daily reconcile alarm — flagged "missing from all three designs" by both
judges — is therefore **mandatory**, not optional, in step 4.

## Test posture

- **Tier 1 (JVM, every build):** occurrence math including the measured
  15-month forward jump, backward-step dedup, DST gap/continuity, grace
  window; ClockGuard axis logic; repository validation (exploding-DAO fakes
  prove rejection precedes DAO contact); JsonDump escaping.
- **Tier 2 (on-watch, per session):** seeding, ordering, FK cascade +
  worklists, LEFT JOIN projection, InvalidationTracker, full schedulePlan
  path against real SQLite 3.22, done-task suppression — plus
  `DeviceTimeSanityTest`, a tripwire asserting the watch clock is sane and
  `auto_time` is still on (the lived incident, weaponized as a test).
- House rule: device SQLite is 3.22 — no UPSERT, no window functions.

## Cut list (with re-entry paths)

Stored next-fire (never — it's a pessimization here), task notes/priority/due
(ALTER TABLE later), `doneAt`, persisted snooze (nullable column later),
monthly/interval rules (`ruleType` column later), running-timer table (SP
contract instead), preset sort order, extra indexes, TypeConverters, mappers,
Hilt, multi-module. First migration ships with whichever of these arrives
first; the harness (exportSchema + committed `schemas/1.json`) is pre-wired.
