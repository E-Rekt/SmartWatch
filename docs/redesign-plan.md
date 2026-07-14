# CZTask v2 â€” Merged Implementation Plan
**"Green Hill Time" as an ADHD performance prosthetic â€” Design 1 (features) Ã— Design 2 (UI), phased A/B/C.**

Repo root: `C:\PROJECT_J_B_B\SmartWatch` Â· Source root: `CZTask/app/src/main/java/com/cztask/` (all file pointers below are relative to it unless noted).

---

## 0. Conflict resolutions (mission wins: ADHD performance)

Rules applied: (1) behavior beats chrome â€” Design 1 wins where they disagree on *what*; Design 2 wins on *how it looks*; (2) nothing depends on a live process; (3) zero-migration items ship first; every schema change lands in exactly one migration per phase; (4) red = "act now / destructive confirm" only, never shame.

| Conflict | Resolution |
|---|---|
| **Ambient face**: Design 1 F1 wants timer arc + checkpoint + ONE Thing in ambient; Design 2 Â§4 says "no arc, time/date + one checkpoint line only" | **Design 1 wins** â€” the watch is in ambient ~95% of the time; a time-blindness aid that vanishes when the screen dims is not an aid. Constraints honored: outline-only 3 px arc, `AMBIENT_GRAY`, existing burn-in jitter applied to everything, AA off in low-bit, minute granularity. |
| Design 1 F1 says "mm:ss remaining" in ambient | **Overruled by measured hardware**: ambient updates on `onTimeTick` (once/min); a frozen seconds value is a lie. Ambient shows whole minutes (`14 MIN`); seconds return in interactive. |
| **Featured task**: Design 1 F2 = pinned task via `pinned_epoch_day` column (migration); Design 2 Â§3.0 = oldest open task, zero migration | **Both, layered**: featured = pinned ?: oldest-open (`ORDER BY created_at ASC LIMIT 1`). Pin lives in **SharedPreferences** (`PinStore`, same pattern as `TimerStateStore`) â€” exactly one pin can exist app-wide, so a per-row column plus a repository-enforced uniqueness invariant is more machinery than the data warrants. Zero migration, ships Phase A. NOT NOW on an unpinned task re-stamps `created_at`; on the pinned task it clears the pin. |
| **Task long-press**: Design 1 F2 = pin; Design 2 Â§3.1 = delete-arm | **Merged into one arm-state**: long-press swaps the row to `â˜… PIN | DELETE?` (delete in `PANIC_RED`), 3 s timeout revert. Pinning must be cheap, deletion must be guarded; one gesture serves both, no dialog. |
| **STEM_2 during an act**: Design 1 F3 = tap extends +5 / hold cancels; Design 2 Â§2.1 = tap opens Timers / hold starts instant timer | **Tap always opens Timers** (one meaning, learnable â€” ADHD needs predictable inputs). **Hold is context-routed**: no act running â†’ instant Focus Act (last duration, bound to featured task); act running â†’ **+5 EXTEND** (the blind "keep going" gesture). **Cancel is never on a stem** â€” destructive action stays on-screen (long-press hero, Design 2), so a pocket press can't kill a timebox. |
| **Alert delivery**: Design 2 Â§3.6 uses `setFullScreenIntent`; Design 1 F4 uses direct `startActivity` from the receiver | **Direct `startActivity` from the reconcile fire path** (legal on API 28, the measured platform advantage) **plus the normal notification always posted first** as the durable layer. Simpler than full-screen-intent plumbing and survives the measured 30â€“60 s process kill either way. |
| **Red usage**: Design 1 restricts red to final-10-s panic; Design 2 also uses it for TIME UP, overdue checkpoint, delete-arm | Red = the **act-now/destructive channel** (panic countdown, TIME UP, overdue checkpoint, delete-arm). All are "act now", none are shame. Hard ban stands on red for missed streaks, bailed acts, or any historical failure state. |
| **Face interactive animation**: Design 2 Â§4 battery rule ("baseline 1 invalidate/min, no idle motion") contradicts the shipped, loved 20 fps interactive loop (spinning rings, scrolling checker) in `face/CzWatchFaceService.kt` | **Keep the shipped face behavior** â€” interactive mode lasts seconds per wrist-raise, the ticker already dies in ambient/invisible (`animTick` guard), and the user loves it. Design 2's stepped-Handler / no-ValueAnimator / â‰¤600 ms one-shot rules apply to all **new** surfaces and new face effects. |
| **Reminder add flow / TimeDial, glyphs, rotary stepper** | No conflict, but they're chrome: deferred to Phase C so Phase A/B budget goes to behavior. The existing 4-step add flow works today. |
| Design 2 seeds timers 25/10/5 | Already effectively seeded (`SeedCallback` in `data/db/AppDatabase.kt`: Pomodoro 1500 s / Break 300 s / 600 s). No work item. |

---

## 1. Phase A â€” this week (highest ADHD impact Ã· effort, **zero schema migration**)

Goal: the wrist answers "what should I be doing right now, and how long do I have?" continuously â€” including in ambient â€” and starting the right work is one press.

### A1. Ambient HUD (Design 1 F1 â€” Top-5 #1)
- **File:** `face/CzWatchFaceService.kt`, the ambient branch of `onDraw` (the early-return block that currently draws gray time/date only).
- Render, all under the existing burn-in jitter `j`, AA rules unchanged:
  - running timer â†’ outline bezel arc, 3 px stroke, `AMBIENT_GRAY`, inset 14 (reuse `arcRect` with a thinner ambient `Paint`), + `14 MIN` (whole minutes, pixel 16 px) â€” data from `runningTimer()` exactly as interactive does;
  - next checkpoint line (reuse `checkpointLine`, already computed in `refreshData()`), pixel 16 px `#606060` at ~y 232;
  - the ONE Thing (A2), 12-char pixel line, dim gray.
- `onTimeTick` already calls `refreshData()` + `invalidate()` â€” no new scheduling. No new data model.

### A2. The ONE Thing (Design 1 F2 â€” Top-5 #2)
- **New file:** `data/time/PinStore.kt` â€” SharedPreferences (`pinned_task_id`, `pinned_epoch_day`), `commit()` like `TimerStateStore`; `get(today)` returns null and self-clears when the epoch-day is stale or the task is gone/done (validate via `TaskDao`).
- **`ui/TasksActivity.kt`:** replace the instant-delete `onLongPress` (currently `repo.delete` inside `appScope.launch`) with the merged **arm-state row** (`â˜… PIN | DELETE?`, 3 s revert). Arm-state handling goes in `ui/ListKit.kt` `RowAdapter` (a transient `armedPosition`; rows are rebound via `submit`, no new view type needed yet).
- **Face:** `refreshData()` in `face/CzWatchFaceService.kt` also reads `PinStore` + task title; draw a 12-char pixel-font line under the RINGS row (interactive + ambient).
- **Tile:** `tile/CzTileService.kt` `layout()` â€” one extra `text()` line for the pinned title (system sans caps; tiles 1.1 can't load Press Start 2P).

### A3. NOW home v1 (Design 2 Â§3.0, scoped)
- **File:** `ui/MainActivity.kt` (rebuild) + **new** `ui/NowCardView.kt` (canvas, all Paints/RectFs pre-allocated â€” zero allocation in `onDraw`, 96 MB heap).
- One `WearableRecyclerView`, edge-centering off when hero present (`setUpWearList(centering: Boolean)` overload in `ui/ListKit.kt`, plus a `Row.Hero` view type); scroll snaps to hero on every `onResume` (process dies in 30â€“60 s; resume is the common case).
- Hero states this phase: **A** timer running (arc + pixel-48 countdown + bound-task label, tap â†’ Timers), **C** featured task (pinned ?: oldest-open via new `TaskDao` query `SELECT * FROM task WHERE done = 0 ORDER BY created_at_utc_millis ASC LIMIT 1`; title sans bold; **`â–¶ GO 25:00`** pill starts the act; long-press title = NOT NOW re-stamps `created_at` â€” new `TaskDao.touch(id, now)` update), **D** all clear (sparkle + next checkpoint). State **B** (overdue checkpoint) arrives in Phase B with `AlertActivity`.
- Menu rows (Tasks / Checkpoints / Timers with status subtitles) below the fold. `! CLOCK` strip replaces the current `clock_warning` menu row when `ServiceLocator.lastClockStatus != ClockStatus.OK`.
- **GO = start bound act:** `data/time/TimerStateStore.kt` gains backward-compatible SP keys `K_TASK_ID`, `K_LABEL`, `K_PLANNED_SECONDS` (defaults preserve old records); `timer/TimerService.kt` `ACTION_START` accepts task extras and saves them; `alarm/Notifications.kt` `timerRunning()` shows the label. Last-used focus duration = one more SP key (default 1500).
- **STEM_2 hold:** in `MainActivity.onKeyDown`, use `event.startTracking()` + `onKeyLongPress` (measured hold-repeat at ~385â€“600 ms on STEM_2 only) â†’ instant act on featured task; tap (in `onKeyUp`, no long-press fired) keeps opening `TimersActivity`. STEM_1 stays sacred: `QuickTaskActivity`.

### A4. Face quick wins (Design 2 Â§4 items 1, 5, 6)
- **File:** `face/CzWatchFaceService.kt`.
  1. Checkpoint proximity swap in `refreshData()`: next fire â‰¤20 min â†’ `IN 12 MIN MEDS`, color `VALUE_WHITE`; else `08:00 MEDS` in `DATE_GRAY`. Biggest single anti-time-blindness change; rides the existing per-minute tick.
  2. Clock-guard dot: `ServiceLocator.lastClockStatus != OK` â†’ pixel `!` 16 px `PANIC_RED` beside the date.
  3. `onTapCommand`: replace the `y > 200` rule â€” timer running â†’ open Timers, else â†’ NOW. One zone, state-routed.

### A5. Timebox protection (Design 2 Â§3.3, the safety subset)
- **File:** `ui/TimersActivity.kt` `render()`: remove tap-to-cancel on the running row (`onTap = { TimerService.cancel(...) }` and the `timer_tap_to_cancel` subtitle). Replace with long-press-to-cancel; single tap flashes `HOLD TO STOP` hint. A running timebox must not die to a sleeve tap. (Full hero canvas card is Phase C; this is the two-line behavioral fix.)

### Phase A schema: **none.** SP additions only (`PinStore`, `TimerStateStore` keys â€” both backward-compatible).

### Phase A on-device verification
- Dev sessions: `adb shell svc power stayon true` (measured: stops the 30â€“60 s homing during testing); `stayon false` after.
- **Process-death drills use `adb shell am crash com.cztask` (alarms survive) â€” never `am force-stop` (the OS cancels alarms and blocks receivers).**
- Ambient: wrist-drop with a timer running â†’ arc + minutes + checkpoint + ONE Thing visible; jitter shifts the whole cluster over 3 minutes; low-bit property â†’ AA off. Overnight ambient battery drain vs. pre-change baseline (thin gray arc should be noise; confirm %/hour).
- GO: tap pill â†’ notification shows task label; crash the process mid-act â†’ countdown survives (SP + backup alarm `Ids.RC_TIMER_ELAPSED_ALARM`); reboot mid-act â†’ "timer canceled by restart" once (existing `LostToReboot`).
- STEM_2: tap opens Timers; hold â‰¥600 ms starts act; verify no double-fire (tap action must not also run on the long-press).
- Pin: long-press â†’ arm row â†’ PIN; face + tile show it; fake next-day (change watch date via settings, not `cmd alarm set-time`, to avoid ClockGuard noise â€” or just wait) â†’ pin expired.
- `adb logcat -s CZTASK_BENCH`: cold start and heap after the NOW rebuild â€” regression gate vs the recorded 786 ms / ~29 MB PSS.

---

## 2. Phase B â€” the core loop (one migration; Focus Act, launch prompts, rings)

Goal: initiation â†’ sustained attention â†’ clean exit â†’ immediate reward, every layer process-death-proof.

### Migration 2 (single migration, `data/db/AppDatabase.kt` â†’ version 2; commit `schemas/2.json`; **no destructive fallback exists â€” this must be right**)
```sql
ALTER TABLE reminder ADD COLUMN prealert_minutes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE reminder ADD COLUMN default_duration_seconds INTEGER;
ALTER TABLE reminder ADD COLUMN snooze_until_utc_millis INTEGER;
ALTER TABLE reminder ADD COLUMN launch_mode INTEGER NOT NULL DEFAULT 0;

CREATE TABLE focus_session (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER REFERENCES task(id) ON DELETE SET NULL,
  planned_seconds INTEGER NOT NULL,
  started_utc_millis INTEGER NOT NULL,
  ended_utc_millis INTEGER,
  outcome INTEGER NOT NULL DEFAULT 0,      -- 0 done, 1 bailed, 2 lost-to-reboot
  extended_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX index_focus_session_task_id ON focus_session(task_id);

CREATE TABLE daily_tally (
  epoch_day INTEGER PRIMARY KEY,
  acts_completed INTEGER NOT NULL DEFAULT 0,
  tasks_done INTEGER NOT NULL DEFAULT 0,
  rings INTEGER NOT NULL DEFAULT 0
);
```
House rules hold (SQLite 3.22): tally upsert = `UPDATE` then `INSERT`-if-0-rows inside a `@Transaction` DAO method; streak/emerald math in Kotlin over â‰¤30 rows. `ON DELETE SET NULL` (not CASCADE): sessions are history, they outlive their task. Room entities in `data/db/Entities.kt` must match this DDL exactly (Room validates at open); new DAOs in `data/db/Daos.kt`; repositories in `data/repo/Repositories.kt` (`markFired` also clears `snooze_until`).

### B1. Focus Act, full (Design 1 F3)
- **`contract/Ids.kt`:** `RC_ACT_WARN = 13`, `RC_ACT_OVERRUN = 14`, `RC_CAPTURE_WATCHDOG = 15` (reserved for C), `NOTIF_ACT_STATUS = 5`, `NOTIF_CHECKPOINT_PROMPT = 6`.
- **`timer/TimerService.kt`:** phases RUN â†’ WARN (T-5 single distinct haptic) â†’ DONE (act-clear) â†’ OVERRUN (+10 min "SURFACE?" double-buzz with `+5 / DONE`, one escalation at +25, never faster). **Every phase boundary is its own exact `ELAPSED_REALTIME_WAKEUP` alarm** (RC 13/14) landing in `alarm/AlarmReceiver.kt` â€” the in-service `Handler` tick is best-effort UX only; the alarms are truth, same three-layer survivability as today. `ACTION_EXTEND` (+5, increments `extended_count`; wired to STEM_2 hold per Â§0 and the SURFACE pill).
- **`data/time/TimerStateStore.kt`:** add `K_PHASE`, `K_WARN_FIRED`, `K_EXTENDED_COUNT` (backward-compatible defaults).
- **Session log:** write `focus_session` at start; stamp `ended/outcome` at DONE (0), cancel (1), and in the `LostToReboot` handling path (2) â€” `alarm/BootReceiver.kt` / wherever `recover()` first observes it (the record self-clears, so write the row before `clear()`).
- **Act-clear reward:** 3 s overlay (rings collected, time bonus if bound task checked) on `NowCardView`/Timers hero + `[30,60,30,60,120]`-class haptic; face all-clear one-shot (Design 2 Â§4.3: cache last `openTasks`, play 6-frame sparkle burst on >0 â†’ 0 while visible, never loops).
- Optional step-detector check at SURFACE (â‰¥20 steps in last 2 min â†’ skip prompt) â€” batched `TYPE_STEP_COUNTER`, no wakeups, no permission.

### B2. Launch-Prompt Reminders (Design 1 F4) + NOW state B
- **New file:** `ui/AlertActivity.kt` â€” full-screen forced choice per Design 2 Â§3.6 skins: checkpoint-fired (blue star post, **START / SNOOZE 10** when `launch_mode=1`, else **DONE**) and timer-done (`TIME UP` pixel 48 red, 1 Hz flash first 10 s). `setShowWhenLocked(true)` + `setTurnScreenOn(true)` (API 27+). Manifest entry with `launchMode="singleTop"`, `excludeFromRecents`.
- **`alarm/ReminderScheduler.kt` `reconcile()`:** in the fire loop, after `Notifications.postReminder(...)` (durable layer, always), branch on `launch_mode` â†’ `context.startActivity(AlertActivity intent, FLAG_ACTIVITY_NEW_TASK)` â€” legal on API 28, no BAL restriction. Mutex/idempotency contract unchanged.
- **`data/time/NextFireCalculator.kt`:** a non-null future `snooze_until_utc_millis` becomes the reminder's sole next occurrence (pure function of the row; JVM tests for snooze Ã— backward-jump dedup Ã— grace window). `markFired` clears it.
- **START** = Focus Act on `reminder.task_id` with `default_duration_seconds ?: last-used`. **SNOOZE 10** = persist `snooze_until` + `reconcile()` â€” both end in the mutex; double-delivery stays impossible.
- **`ui/MainActivity.kt`/`NowCardView`:** hero state **B** (overdue checkpoint, red ball + DONE pill; acknowledge = cancel notif + mark linked task done).

### B3. Transition pre-alerts (Design 1 F5)
- **`data/repo/Repositories.kt` `schedulePlan()`:** emit pre-alert instants (`occurrence âˆ’ prealert_minutes`) as synthetic candidates into the **same coalesced** next-fire; `dueNow` entries gain a `kind` (FIRE vs PREALERT). Pre-alert delivery = one short haptic, no screen (`alarm/ReminderScheduler.kt` branches on kind; no `markFired` rigor needed â€” worst case is a duplicate buzz, but it runs inside the mutex anyway). No new alarm slot, no new race.

### B4. Rings & Emeralds (Design 1 F6)
- Tally writes at act completion (`TimerService` DONE path) and `TaskRepository.setDone()`; +1 ring per act, +10 for the ONE Thing. Day rollover lazily on read (the daily backstop reconcile touches the app daily anyway).
- Face footer: today's rings count in the HUD grammar; 7-day streak â†’ tiny emerald gems above the checker; a missed day **fades** the newest emerald over 3 days (dim alpha steps), never shatters, regainable. No history screens. Renders in `face/CzWatchFaceService.kt` + `NowCardView`.

### B5. Capture reward + shutdown ritual (Design 2 Â§3.5, Design 1 F9)
- **`ui/QuickTaskActivity.kt`:** replace the `Toast` with the 500 ms ring-collect confirm frame (5 quantized frames, 40 ms buzz, then `finish()`); silent finish on cancel.
- **F9 stretch:** one system-seeded evening reminder ("Pick tomorrow's ONE Thing") â†’ two taps: pick task (sets `PinStore` for tomorrow), optionally bind a morning launch-prompt. Pure reuse of A2 + B2 plumbing.

### Phase B on-device verification
- **Migration:** install v2 over a populated v1 DB on-watch â†’ data intact, `cztask.db.bak` + `-wal` sidecar written (`ServiceLocator` pre-open hatch), new DDL runs on real SQLite 3.22 (Tier 2 instrumented migration test; the dev machine's newer SQLite proves nothing).
- **Focus Act survivability:** start 15-min act â†’ `am crash` immediately â†’ T-5 buzz still fires (RC 13), completion fires (backup alarm), overrun SURFACE fires at +10 with the process dead (RC 14). Reboot mid-act â†’ `focus_session.outcome=2` row exists (verify via `JsonDump`).
- **Launch prompt:** `launch_mode=1` reminder 2 min out, screen off, process crashed â†’ screen turns on with AlertActivity; if the measured aggressive homing dismisses it, the notification still stands (this is the acceptance criterion, not a bug). SNOOZE 10 â†’ crash process â†’ snoozed fire still arrives (it's in the DB, rides the coalesced alarm).
- **No double-fire:** alarm into dead process (app-start reconcile + receiver reconcile race) still single-delivers â€” existing mutex, retest with launch_mode path.
- **Tally/streaks:** JVM tests for streak/fade math; on-watch, seed `daily_tally` via a debug hook and eyeball emeralds/fade states; confirm rings increment on act-clear and ONE Thing check-off.
- Pre-alert: verify buzz at T-5 with no screen wake, and that the main fire still arrives (coalescing didn't eat it).

---

## 3. Phase C â€” the full Sonic shell + honest biometrics (probe-gated)

Goal: one visual system everywhere; add the morning HRV checkpoint only if the hardware proves out.

### C1. UI system pass (Design 2 Â§Â§1â€“3, 5)
- **New** `ui/Glyphs.kt`: port the face's private `drawRing/drawSparkle/drawStarPost` into a shared parameterized `Glyphs` object + `GlyphView`; add arc-clock/stop/plus. Kill all text/emoji glyphs (`â—‹ âœ“ â— â—Œ â± â– ` in `TasksActivity`, `RemindersActivity`, `TimersActivity`).
- **`ui/ListKit.kt`:** `Row.Header`, `Row.Item.glyph: GlyphKind`, `RotaryStepper` hooked via `dispatchGenericMotionEvent` (**the probe-mandated hook** â€” `onGenericMotionEvent` only sees scroll-edge leftovers): accumulate Â±0.035 quanta, 3 quanta = one row-step, fast mode at <70 ms gaps.
- **`row_two_line.xml` / `row_center_text.xml`** (`CZTask/app/src/main/res/layout/`): `minHeight` 48 dp; glyph `TextView` â†’ `GlyphView`.
- **New** `ui/TimeDialActivity.kt` (canvas, crown-driven, relative echo "in 3 h 40 min"): replaces the hour+minute `PickerActivity` screens in `RemindersActivity` (4â†’3 step add flow) and the 11-row minutes list in `TimersActivity.addPreset()`.
- **`ui/RemindersActivity.kt`:** "Checkpoints" strings, glyph states, relative-time subtitle (â‰¤60 min â†’ `Â· in 12 min`, white), delete-arm.
- **`ui/TimersActivity.kt`:** full-bleed running hero card (port the face's drowning-countdown rules verbatim).
- **Face** remaining items: arc tip glint (even seconds, rides existing 1 Hz), minute pulse (2 frames, boolean default-ON).
- **`tile/CzTileService.kt`:** `BTN_BG 0xFF203048` â†’ `SURFACE 0xFF141414`; drop the `"no checkpoint"` gray filler; â‰¤20 min relative swap; `ALL CLEAR` in gold.
- Motion master table (Design 2 Â§5) governs everything: 4â€“6 quantized frames via `Handler.postDelayed`, `overridePendingTransition(0,0)` everywhere, **no ValueAnimator/choreographer in the face, ever**; forbidden list (idle loops, parallax, animated empty states) enforced on new code.

### C2. Probe spike first (house rule: measure, don't guess)
Extend **`CZProbe/`** with a `BODY_SENSORS` session: register sensor type 65574 (`lifeq_lel_rr`) for 5 min on-wrist and log â€” does it emit without Fossil's wellness stack? Real RR ms or opaque floats? Warm-up latency (PPG typically 10â€“20 s)? Do daily/basal RHR virtual sensors tick? **F7's design freezes only after that logcat exists.**

### C3. Morning Checkpoint (Design 1 F7, only if C2 passes) â€” Migration 3
```sql
CREATE TABLE biometric_sample (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  captured_at_utc_millis INTEGER NOT NULL,
  kind INTEGER NOT NULL,
  mean_hr REAL, rmssd_ms REAL,
  rr_count INTEGER NOT NULL DEFAULT 0,
  quality INTEGER NOT NULL DEFAULT 0
);
```
- **New** `sensors/CheckpointCaptureService.kt`: bounded foreground service, 4-min watchdog alarm (RC 15), partial wakelock for the capture only. Off-body â†’ on-body + stationary gates the prompt; capture doubles as paced breathing.
- Artifact screening exactly per Design 1 Â§5 (RR 300â€“2000 ms, >20% successive-diff drop, â‰¥120 clean beats, stationary + on-body throughout; else `quality=BAD`, nothing user-visible).
- Manifest: `BODY_SENSORS` (runtime, requested lazily at first opt-in with the plain-language screen; denial hides F7 entirely). Output: **one** baseline-relative pacing sentence on the face on genuinely low days ("LOW RING DAY â€” SHORT ACTS" â†’ 15-min act defaults); silence on normal days; never interrupts, never scores.

### C4. Break-that-counts (Design 1 F8)
Between acts: START "charges" after ~20 steps (batched step detector delta); never enforced. One bit of transient state on the break screen.

### C5. Explicitly still cut
Real-time focus/stress detection (the sensor cannot do it honestly), continuous HR / body battery (~16%/day of 300 mAh for an unusable signal), body doubling (no network sociality on a standalone watch), stats dashboards, social anything, notification mirroring, complications (no host faces post-debloat), red/shame states, streak shattering. **Routine chains (F10)** stay deferred â€” revisit only if morning initiation is still the top pain after A+B have soaked.

### Phase C on-device verification
- Rotary stepper: guided rotation test Ã  la CZProbe â€” row-per-3-quanta, fast mode engages, no double-steps at scroll edges.
- TimeDial: crown Â±5 min, hour-boundary haptic only, relative echo correct across midnight wrap and DST days (JVM tests for the math).
- Probe spike: the logcat answers Â§C2's four questions before any F7 code.
- Capture: quality gate rejects a deliberately fidgety capture; watchdog kills a hung capture at 4 min; PPG-on time <15 min/day (`dumpsys batterystats` before/after); permission-denied path hides F7 with zero nagging.
- Tile: verify on the real 2.66 home (renderer quirks are device-specific).

---

## 4. Constraint flags (where the source designs bend the measured reality)

1. **F1's "mm:ss in ambient" is impossible honestly** â€” ambient ticks once/min; show whole minutes. (Resolved Â§0.)
2. **Ambient arc = burn-in risk** on AMOLED: outline-only â‰¤3 px, `AMBIENT_GRAY`, inside the existing jitter, AA off in low-bit. Filled arcs / bright gold in ambient are forbidden.
3. **Design 2's face-battery rule contradicts the shipped face** (20 fps interactive loop). Not a violation â€” interactive is seconds-long per wrist-raise and the ticker provably stops (`animTick` guard) â€” but the rule as written would delete loved behavior. Applied to new surfaces only.
4. **OVERRUN must not trust the foreground service.** The measured 30â€“60 s kill applies; Design 2's hero UI recomputes phase from `TimerStateStore` on every resume, and every phase boundary (WARN/DONE/+10/+25) is its own exact elapsed alarm. Any implementation that keeps phase only in service memory is wrong on this device.
5. **AlertActivity can be homed away** (measured aggressive backgrounding, `HomeActivity2` resumes). The always-posted notification is the durable layer; snooze lives in the DB, never in activity memory. An alert that exists only as an activity is a data-loss bug here.
6. **`am force-stop` in testing silently cancels alarms** â€” all process-death drills use `am crash` / natural kills, or every survivability test is invalid.
7. **SQLite 3.22:** no UPSERT (3.24), no window functions (3.25) â€” `daily_tally` is UPDATE-then-INSERT in a `@Transaction`; streaks in Kotlin. Dev-machine SQLite passing means nothing; Tier 2 on-watch tests are the enforcement.
8. **Migration discipline:** no destructive fallback exists (`ServiceLocator` builder deliberately omits it); Migration 2/3 each need the committed schema JSON + an instrumented migration test + the `.bak` escape-hatch check.
9. **Heap (96 MB, lowRam):** `NowCardView`/`Glyphs` allocate nothing per frame; share the face's cached checker bitmap instance (~106 KB) rather than a second copy; `notifyDataSetChanged` stays (no DiffUtil churn).
10. **Tiles 1.1:** no custom fonts, no canvas â€” ONE Thing and relative times render in system sans caps; don't chase pixel-font parity there.
11. **F3's spoken-intent ritual must stay off the GO path** â€” GO is one tap by contract; the intent capture is optional and skippable or it violates the â‰¤2-interaction gate.
12. **Tally vs the lived clock incident:** `daily_tally` keys on local epoch-day; a wild clock writes cosmetic garbage rows. Acceptable (ClockGuard banners the real problem; JsonDump audits), but gate emerald *loss* on `ClockStatus == OK` so a clock jump can never eat a streak â€” that's an RSD rule, not a nicety.
13. **BODY_SENSORS is API 28 runtime** (no BACKGROUND variant until 33): grant is process-lifetime-bounded, which is exactly why captures run in a watchdogged foreground service. Never request at app start.
14. **Battery envelope:** Tier 0 sensors ~free (hardware-batched); Tier 1 PPG <15 min/day â‰ˆ <1% of 300 mAh; Tier 2 continuous PPG stays rejected (~16%/day before SoC wake cost). Any feature that needs the process alive in background is dead on arrival â€” 30â€“60 s, measured.

---

## 5. Product thesis

After this redesign the watch is a single-purpose **external executive function**: it holds the one intention that matters (the ONE Thing, always on the wrist, even asleep), converts formed intentions into started work without routing through willpower (launch-prompt checkpoints, one-press GO, STEM-hold instant acts), makes time physically visible so it can't silently evaporate (ambient depleting ring, relative "IN 12 MIN" everywhere, T-5 landing-gear buzzes, kind hyperfocus surfacing), and pays out dopamine at the exact moment of completion in a grammar the user already loves (rings, act-clear, fading-never-shattering emeralds) â€” while refusing, structurally, to become a distraction: no feeds, no stats, no social, no fake biometrics, nothing that moves when nothing is happening, and every promise backed by alarms and on-disk state that survive a process the OS kills within the minute. It is not a smartwatch with productivity apps; it is a Green Hill-skinned prosthetic whose one job is that critical work gets started, stays held, and gets finished.

