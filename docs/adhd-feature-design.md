# CZTask ADHD Performance Layer â€” Feature Design v1

**Scope:** the feature set that turns CZTask (C:\PROJECT_J_B_B\SmartWatch\CZTask) from a task/reminder/timer app into an ADHD performance prosthetic, designed strictly against the measured device (docs/probe-results.md) and the shipped architecture (docs/data-layer-design.md, mutex-reconcile `ReminderScheduler`, elapsed-axis `TimerService`, `CanvasWatchFaceService` "Green Hill Time", `CzTileService`).

---

## 0. TL;DR â€” Top 5 by ADHD-impact-per-effort

| # | Feature | Size | Why it wins |
|---|---------|------|-------------|
| 1 | **Ambient HUD** â€” timer ring + next checkpoint visible in ambient mode | S | The watch is in ambient ~95% of the time; a time-blindness aid that vanishes when the screen dims is not an aid. Cheapest change, largest exposure. |
| 2 | **The ONE Thing** â€” a single pinned task, always on the face | S | ADHD intention decay ("out of sight, out of mind") is as damaging as time-blindness. One nullable column, one line of canvas text. |
| 3 | **Focus Act** â€” single-task timebox with T-5 warning, overrun nudge, act-clear reward | M | The core loop: initiation â†’ sustained attention â†’ clean exit â†’ dopamine. Builds directly on `TimerService` + `TimerStateStore`. |
| 4 | **Launch-Prompt Reminders** â€” reminders become full-screen START / SNOOZE 10 forced choices | M | Converts "notification I swiped away" into implementation-intention execution. API 28 lets a receiver start an activity directly â€” a genuine platform advantage. |
| 5 | **Rings & Emeralds** â€” daily tally + 7-day streak in the existing Sonic grammar | M | Dopamine attached to *completion*, rendered in the design language the user already loves. No shame states. |

Everything else is phase 2+ or cut (Â§4).

---

## 1. The design razor

Mission: **help one ADHD user start critical work, stay on it, and finish it.** Every feature passes four gates or dies:

1. **Glanceable or invisible.** If it needs >2 interactions or >5 seconds of attention, it's a distraction engine. The face and haptics are the product; activities are plumbing.
2. **Survives process death.** Measured 30â€“60 s background kill â†’ all state in Room or `TimerStateStore`-style SharedPreferences, all future events as elapsed-axis or coalesced RTC alarms through the existing `reconcile()` mutex. No feature may depend on a live process.
3. **No new dopamine leaks.** Nothing scrollable, nothing social, nothing with a feed. Reward moments are bounded, earned, and end.
4. **RSD-safe.** No red failure states, no broken-streak shattering, no guilt copy. Abandoning a session logs quietly. Red means exactly one thing on this watch (final-10-seconds panic) and stays that way.

Non-negotiable substrate (measured): Wear OS 2 / API 28, 96 MB heap / plain Views + canvas, SQLite 3.22 (no UPSERT, no window functions â€” tally/streak math in Kotlin), crown click is HOME (never design on it), STEM_1/STEM_2 real, rotary = Â±0.035 quanta, single coalesced RTC alarm + daily elapsed backstop is the only scheduling pattern.

---

## 2. Survey â†’ mechanisms

What the best current interventions actually do, compressed to mechanisms we can port ([ADDA on time blindness](https://add.org/adhd-time-blindness/), [2025 systematic review of wearables in ADHD](https://pmc.ncbi.nlm.nih.gov/articles/PMC12468562/), [ADHD timer landscape](https://blog.saner.ai/best-adhd-timer/), [ADHD watch roundups](https://brianvanderwaal.com/best-adhd-watch/)):

| Intervention class | Exemplar | Mechanism | Portable to CZ Smart? |
|---|---|---|---|
| Ambient visual time (depleting disk) | Time Timer / Time Timer Watch | Externalizes remaining time; bypasses broken internal clock. Strongest evidence base of the lot. | **Yes, fully** â€” we already draw a depleting bezel ring; extend to ambient. |
| Implementation intentions ("when X, I will Y") | WOOP, clinical practice (Gollwitzer & Sheeran meta-analysis, dâ‰ˆ0.65; Gawrilow's ADHD studies) | Pre-binds cueâ†’action so initiation doesn't route through executive function at the moment of weakness. | **Yes** â€” reminders already fire exactly; upgrade the fire UX to a forced choice that launches work. |
| Timeboxing / Pomodoro | Forest, Flow, every focus app ([survey](https://www.brain.fm/blog/focus-apps-for-adhd)) | Artificial urgency + bounded commitment lowers initiation cost ("only 25 min"). | **Yes** â€” `TimerService` is 80% of it. |
| Transition warnings | Autism/ADHD classroom practice, Apple Watch haptic pre-alerts | Set-shifting is expensive; a T-5 warning lets the brain disengage gradually instead of being yanked. | **Yes** â€” haptic vocabulary + one scheduling tweak. |
| Hyperfocus exit nudges | Apple Watch alarm stacks ([review](https://theadhdlifestyle.com/2021/06/24/apple-watch-for-adhd/)) | Hyperfocus causes time-loss and interoceptive blindness; escalating external interrupts restore agency. | **Yes** â€” elapsed-axis overrun alarms. |
| Body doubling | Focusmate, [FOCO](https://www.tryfoco.com/) (virtual body doubling reduced task avoidance in a Sussex preprint) | Social presence lowers initiation threshold. | **No, honestly.** Standalone watch, no companion, no network sociality. Partial substitute: spoken-intent commitment + end-of-act self-review (Â§3, F3). |
| Streak/reward mechanics | Forest, Finch, Habitica | Immediate reward at completion compensates for ADHD's delayed-reward discounting. Risk: the app becomes the game. | **Yes, bounded** â€” the Sonic grammar (rings, act-clear, emeralds) gives reward moments that *end*. |
| Stress/recovery pacing | Garmin Body Battery, Fitbit EDA | Arousal-aware workload pacing. | **Partially, honestly** â€” trend-grade morning HRV only; no real-time focus/stress detection (Â§5). |
| Routine chains | Tiimo, Routinery | Externalized sequencing removes per-step initiation cost. | Yes but L-sized; phase 3. |

---

## 3. Feature specifications

### F1. Ambient HUD (Top-5 #1) â€” **S**
- **What:** In ambient mode, render (burn-in-safe, low-bit-aware): the running timer/act as a thin outline bezel arc + mm:ss remaining, the next checkpoint line, and the pinned ONE Thing. Today: ambient shows only gray time/date (`CzWatchFaceService.onDraw` ambient branch).
- **Mechanism:** Time-blindness aids only work if they are *continuously* visible. `onTimeTick` gives minute granularity in ambient â€” exactly right for glances; second-precision returns in interactive.
- **Hardware mapping:** Existing `CanvasWatchFaceService`. Thin 3â€“4 px stroke arc (outline only â€” burn-in), reuse the existing jitter. Wrist-tilt gesture already wakes ambient, so a wrist-raise *is* the query gesture: zero interactions.
- **Data model:** none. **Engine:** none (reads `TimerStateStore` + `schedulePlan()` exactly as the interactive path does).

### F2. The ONE Thing (Top-5 #2) â€” **S**
- **What:** Exactly one task can be pinned as today's critical task. It renders on the face (interactive + ambient) as a 12-char pixel-font line under RINGS, on the tile, and at the top of `TasksActivity`. Pinning is a long-press in the task list; pin auto-expires at end of day.
- **Mechanism:** ADHD working memory doesn't hold intentions; the environment must. This is object permanence for the day's most important commitment â€” and it collapses choice paralysis ("what should I do?" â†’ it's on your wrist).
- **Data model:** `task` + `pinned_epoch_day INTEGER NULL` (migration 2, ALTER TABLE â€” already on the cut-list re-entry path). One task enforced in `TaskRepository` (clear previous pin on set).
- **Engine:** none. Face/tile already refresh via reconcile's tile update + `onTimeTick`.

### F3. Focus Act (Top-5 #3) â€” **M**
- **What:** Start an "Act": pick a task (default: the ONE Thing) + duration (rotary dial; presets 15/25/45). The face becomes the act: task title, depleting gold bezel ring, elapsed context. At T-5 min: single distinct haptic (transition warning). At zero: act-clear screen â€” "ACT CLEAR", rings collected, time bonus if the bound task was checked off â€” 3 seconds, then gone. Overrun (user keeps going): at +10 min a gentle double-buzz "SURFACE?" prompt with +5 / DONE; escalates once more at +25, never nags faster. STEM_2 during an act = extend +5 (measured hold-repeat on STEM_2 reserved for cancel).
- **Mechanism:** Timeboxing (bounded commitment lowers initiation cost) + transition warning (cheap set-shift) + hyperfocus exit (the Sonic drowning-countdown made kind) + immediate completion reward. The optional start ritual â€” quick-capture flow asks "what will you do?" and stores the spoken intent â€” borrows the pre-commitment half of body doubling: you told someone (the watch), and it will ask.
- **Hardware mapping:** All elapsed-axis; no sensors required. Optional step-detector check at "SURFACE?" â€” if â‰¥20 steps in last 2 min, user already moved; skip the prompt.
- **Data model:** new table `focus_session(id PK, task_id NULL REFs task, planned_seconds, started_utc_millis, ended_utc_millis NULL, outcome INT /*0 done,1 bailed,2 lost-to-reboot*/, extended_count INT)`. `TimerStateStore` SP gains keys (backward-compatible): `session_task_id`, `planned_seconds`, `phase`, `warn_fired`, `extended_count`.
- **Engine:** `TimerService` grows phases RUN â†’ WARN â†’ DONE â†’ OVERRUN. T-5 warning and overrun nudges are additional exact `ELAPSED_REALTIME_WAKEUP` alarms (new `Ids`: RC 13â€“15, NOTIF 5) so they fire even if the process is dead â€” same three-layer survivability as today (service tick, backup alarm, SP truth). Reboot: existing `LostToReboot` path records `outcome=2`.

### F4. Launch-Prompt Reminders (Top-5 #4) â€” **M**
- **What:** A reminder fire optionally opens a full-screen forced choice instead of a passive notification: task title + **START** (launches a Focus Act on the bound task, duration = the reminder's stored intent) / **SNOOZE 10** (explicit, persisted). No swipe-to-dismiss into oblivion; not touching it leaves the normal notification as fallback. Per-reminder opt-in ("launch mode") so pill-style reminders stay plain.
- **Mechanism:** Implementation intentions: the plan "at 14:00 I start the report for 25 min" is formed once, when capacity is high, and executed by cue. The forced binary defeats notification blindness â€” the ADHD reflex of clearing alerts without processing them.
- **Hardware mapping:** API 28 has **no background-activity-launch restriction** (that arrived in Android 10) â€” `AlarmReceiver` can `startActivity` directly; no full-screen-intent permission dance. Same class of platform advantage as free exact alarms.
- **Data model:** `reminder` + `default_duration_seconds INTEGER NULL` (the "then I willâ€¦" half), `snooze_until_utc_millis INTEGER NULL` (already the planned nullable-column re-entry), `launch_mode INTEGER NOT NULL DEFAULT 0`.
- **Engine:** `NextFireCalculator` treats a non-null, future `snooze_until` as the reminder's next occurrence (cleared on fire). Snooze/START both end in `reconcile()` â€” the mutex already makes double-delivery impossible. The fire path in `ReminderScheduler.reconcile()` branches on `launch_mode`.

### F5. Transition warnings on reminders â€” **S** (rides F4)
- **What:** Optional per-reminder T-5 pre-alert: one short haptic, no screen, no sound â€” "landing gear down."
- **Mechanism:** Same set-shifting economics as F3's warning, applied to appointments ("leave for dentist").
- **Data model:** `reminder` + `prealert_minutes INTEGER NOT NULL DEFAULT 0`.
- **Engine:** `schedulePlan()` emits pre-alert instants as synthetic occurrences feeding the *same* coalesced RTC alarm â€” no new alarm slot, no new race. Pre-alerts don't need `markFired` rigor (worst case is a duplicate buzz), but they run inside the reconcile mutex anyway.

### F6. Rings & Emeralds (Top-5 #5) â€” **M**
- **What:** Completing an Act = collect a ring; checking off the ONE Thing = 10 rings (the Sonic economy). The face footer shows today's rings; 7 consecutive days with â‰¥1 completed Act = a Chaos Emerald (max 7 shown as tiny gems above the checker). A missed day *fades* the newest emerald over 3 days before it's gone â€” decay, never shattering, and regainable. No numbers screens, no history charts, no leaderboards.
- **Mechanism:** ADHD reward discounting means distant payoffs don't motivate; the ring buzz at act-clear is *immediate*. Streaks add a light loss-aversion pull, and the fade rule keeps it RSD-safe.
- **Data model:** `daily_tally(epoch_day INTEGER PK, acts_completed INT, tasks_done INT, rings INT)`. SQLite 3.22: no UPSERT â†’ UPDATE-then-INSERT-if-0-rows inside a transaction; streak/emerald math in Kotlin over the last ~30 rows (microseconds).
- **Engine:** tally writes happen at act completion and `setDone()`; day rollover handled lazily on read (no midnight alarm needed â€” the daily backstop reconcile already touches the app daily).

### F7. Morning Checkpoint (HRV trend) â€” **M**, gated on a probe (Â§5)
- **What:** One optional prompted ritual: after first wear-on of the day (off-body â†’ on-body transition + stationary), the watch offers a 3-minute still capture ("CHECKPOINT â€” sit still, breathe"). Collects RR intervals (sensor type 65574 `lifeq_lel_rr`), computes RMSSD + mean HR, stores the trend. Output is exactly one sentence of pacing advice on the face for the day: baseline-relative only â€” "LOW RING DAY â€” SHORT ACTS" (suggest 15-min defaults) vs nothing at all on normal days. The capture doubles as a paced-breathing minute â€” the one legitimately evidence-adjacent "mindfulness" surface, for free.
- **Mechanism:** Arousal-aware pacing without pretending to read minds: on genuinely low-recovery days, shorter timeboxes preserve the completionâ†’reward loop instead of setting up failure.
- **Data model:** `biometric_sample(id PK, captured_at_utc_millis, kind INT, mean_hr REAL, rmssd_ms REAL, rr_count INT, quality INT)`. Quality flag from artifact screening (Â§5).
- **Engine:** none scheduled â€” opportunistic prompt from the on-body event when the app happens to be alive, plus the existing daily reconcile as fallback prompt. Capture runs in a short foreground service (survives the 30â€“60 s kill).

### F8. Break-that-counts â€” **S** (rides F3)
- **What:** Between Acts, the break screen asks for 20 steps (step detector) before the next Act's START button brightens. Not enforced â€” START always works â€” just visibly "charged" by movement.
- **Mechanism:** Movement measurably improves subsequent attention; more importantly it makes the break *restorative* instead of a phone-scroll trap. The nudge is one bit of state, not a lecture.
- **Data/engine:** none persistent; step-count delta read via hardware-batched `TYPE_STEP_COUNTER` (no wakeups, ~zero battery).

### F9. Shutdown ritual â€” **S/M**, phase 2
- **What:** One evening reminder (system-owned row): "Pick tomorrow's ONE Thing." Two taps: choose task, optionally bind a launch-prompt for a morning time.
- **Mechanism:** Forms tomorrow's implementation intention tonight, when there's no morning executive-function tax; kills wake-up decision paralysis.
- **Data model:** none beyond F2/F4 columns. **Engine:** ordinary reminder.

### F10. Routine chains â€” **L**, phase 3 only
Sequential per-step timers ("morning: meds 2m â†’ shower 10m â†’ dress 5m"). High ADHD value, but two new tables + a new runner UI + chain semantics in the timer engine. Ship F1â€“F6 first; revisit only if morning initiation remains the top pain.

---

## 4. Cut list (ruthless, with reasons)

| Cut | Why |
|---|---|
| **Real-time focus/stress detection** | The sensor cannot do it honestly (Â§5). Shipping a fake signal erodes trust in the honest ones. |
| **Continuous HR / all-day "body battery"** | 10â€“20%/day of a 300 mAh battery plus keeping our killable process fed, for a heuristic we can't validate. Rejected. |
| **Body doubling** | Needs another human; no companion, no sociality on a standalone Wear 2 watch. Substitute shipped inside F3 (spoken intent + end review). |
| **Stats dashboards / history charts** | Phone-scale fiddling surface; invites app-as-distraction. `JsonDump` remains the export path if analysis is ever wanted off-watch. |
| **Social/leaderboards/sharing** | Anti-mission. |
| **Standalone breathing app** | Folded into F7's capture window; a separate app is a separate distraction. |
| **Notification mirroring/integrations** | Nothing else runs on this watch by design (21-package debloat) â€” keep it that way. |
| **Complications** | No host faces remain post-debloat (already established). |
| **Red/shame states, streak shattering** | RSD-safe rule; emeralds fade and return. |

---

## 5. The biometric layer, honestly

### What a 2020 PixArt PAH8131 + LifeQ stack CAN give us
- **Mean HR at rest** (stationary, good contact): trustworthy to a few bpm.
- **RR intervals â†’ RMSSD during enforced-still windows**: usable as a *personal trend*, not an absolute. Type 65574 (`lifeq_lel_rr`) makes this feasible without beat-detection of our own.
- **Daily/basal RHR, calories** (LifeQ virtual sensors): possibly computed on-hub; **unverified whether they update without Fossil's wellness stack alive**.
- **Off-body detect (PAH8011), step counter/detector, stationary detect, wrist tilt**: essentially free and reliable; these do the gating.

### What it CANNOT do â€” do not design against these
- **Detect "focus."** There is no attentional signature in wrist HR/HRV. Nothing on the market does this from PPG; claims otherwise are marketing.
- **Distinguish stress from caffeine, heat, illness, digestion, or walking to the kitchen.** HR elevation is arousal-nonspecific.
- **HRV under motion or poor contact.** Wrist RMSSD error is ~0.9 ms at optimal contact pressure but **jumps to ~11 ms when pressure drifts** ([Nature Sci Rep 2025](https://www.nature.com/articles/s41598-025-31883-5)); studies report **~17% relative RMSSD error even in controlled conditions** ([Sci Rep, elderly wrist-PPG validation](https://www.nature.com/articles/s41598-021-87489-0)) and **40â€“45% in ecological ones** ([PPG preprocessing analysis](https://arxiv.org/html/2510.06158v1); [motion-artifact review](https://pmc.ncbi.nlm.nih.gov/articles/PMC6387309/)). Conclusion: HRV is valid here **only** in prompted stationary windows with artifact screening, compared against the user's own baseline.
- **Sleep HRV**: the watch charges at night; there is no night data. The morning checkpoint is the honest substitute.

Design consequence: **biometrics inform pacing suggestions only (F7); they never trigger interrupts, never score the user, and the whole layer is optional.** The 2025 [systematic review of wearables in ADHD](https://pmc.ncbi.nlm.nih.gov/articles/PMC12468562/) supports exactly this posture: physiology as enrichment, behavioral scaffolding as the intervention.

### Artifact screening (cheap, adequate)
Within a capture window: drop RR outside 300â€“2000 ms, drop successive-difference outliers >20% (standard filter), require â‰¥120 clean beats out of ~3 min, require stationary-detect true and off-body false throughout; else `quality=BAD`, store nothing user-visible, say "couldn't get a clean read" once.

### Sampling strategy on ~300 mAh
- **Tier 0 (always, ~free):** step counter with max batching latency (hardware FIFO, no app wakeups), off-body, wrist tilt, stationary detect.
- **Tier 1 (scheduled/manual, budgeted):** morning checkpoint 3 min + optional act-bookend 60 s reads. Total PPG-on <15 min/day at ~2 mA sensor+LED â‰ˆ **0.5 mAh/day, <1% of battery**. Wrap captures in a bounded foreground service with a 4-minute watchdog and a partial wakelock only for the capture.
- **Tier 2 (rejected):** continuous PPG â‰ˆ 2 mA Ã— 24 h â‰ˆ 48 mAh (~16%/day) *before* counting SoC wake cost and process churn on a device that kills us in 30â€“60 s. Cost is real, signal is unusable (motion), value is nil.

### Probe first (house rule: measure, don't guess)
Before building F7, extend **CZProbe** with a BODY_SENSORS session: register 65574 for 5 min on-wrist and log â€” does it emit without Fossil's wellness app? Real RR in ms or opaque floats? Latency to first reading (PPG warm-up is typically 10â€“20 s â€” the capture UX must absorb it)? Do daily/basal RHR virtual sensors ever tick? F7's design freezes only after that logcat exists.

### BODY_SENSORS on API 28
- Manifest: `<uses-permission android:name="android.permission.BODY_SENSORS"/>`.
- Runtime: dangerous permission (SENSORS group) â†’ standard `requestPermissions()`; Wear OS 2 renders the grant dialog on-watch. Request it **lazily at first Checkpoint opt-in** with one plain-language screen ("reads pulse rhythm during the 3-minute checkpoint, nothing else, never in background"), never at app start.
- No `BODY_SENSORS_BACKGROUND` exists at API 28 (that's API 33) â€” once granted, access is bounded only by process lifetime, which is why captures run in a foreground service.
- Denial is fully graceful: F7 hides; F1â€“F6, F8â€“F9 are sensor-permission-free.

---

## 6. Consolidated deltas

**Migration 2 (harness pre-wired, schemas/1.json committed):**
- `task` + `pinned_epoch_day INTEGER NULL`
- `reminder` + `prealert_minutes INTEGER NOT NULL DEFAULT 0`, `default_duration_seconds INTEGER NULL`, `snooze_until_utc_millis INTEGER NULL`, `launch_mode INTEGER NOT NULL DEFAULT 0`
- New tables: `focus_session`, `daily_tally`, `biometric_sample` (DDL in Â§3)
- House rules hold: no UPSERT, no window functions; tally upsert = UPDATE-then-INSERT in txn; streaks in Kotlin.

**Engine deltas:**
- `NextFireCalculator`: honor `snooze_until`; emit pre-alert synthetic occurrences into the same coalesced next-fire.
- `ReminderScheduler.reconcile()`: branch fire path on `launch_mode` (direct activity start â€” legal on API 28); unchanged mutex/idempotency contract.
- `TimerService`: phases RUN/WARN/DONE/OVERRUN, extend action, session binding; each phase boundary backed by its own exact elapsed alarm; SP keys added backward-compatibly; `LostToReboot` records session outcome.
- `Ids.kt`: RC 13 = act warn, 14 = act overrun, 15 = capture watchdog; NOTIF 5 = act status, 6 = checkpoint prompt.
- Face: ambient arc + checkpoint + ONE Thing; interactive adds pinned-task line, rings tally, act-clear overlay. Tile: ONE Thing + start-act tap.

**Build order suggestion:** F1 â†’ F2 â†’ F3 â†’ F4+F5 â†’ F6 (one migration covers F2â€“F6) â†’ probe spike â†’ F7/F8 â†’ F9.

---

## Sources

- [Wearables in ADHD: Monitoring and Intervention â€” Where Are We Now? (2025 systematic review, PMC)](https://pmc.ncbi.nlm.nih.gov/articles/PMC12468562/)
- [ADDA â€” ADHD Time Blindness](https://add.org/adhd-time-blindness/)
- [Best ADHD timers 2026 (landscape)](https://blog.saner.ai/best-adhd-timer/) Â· [Best ADHD watches 2026](https://brianvanderwaal.com/best-adhd-watch/) Â· [Focus apps for ADHD](https://www.brain.fm/blog/focus-apps-for-adhd) Â· [Apple Watch for ADHD](https://theadhdlifestyle.com/2021/06/24/adhd-lifestyle/)
- [FOCO â€” AI body doubling for task initiation](https://www.tryfoco.com/)
- [Reliable wrist PPG monitoring by mitigating poor skin-sensor contact (Nature Sci Rep 2025 â€” RMSSD 0.89 ms â†’ 10.95 ms off optimal pressure)](https://www.nature.com/articles/s41598-025-31883-5)
- [Accuracy of HRV from reflective wrist-PPG (Sci Rep â€” ~17% RMSSD MAE)](https://www.nature.com/articles/s41598-021-87489-0)
- [Beyond Motion Artifacts: PPG preprocessing for PRV estimation (arXiv â€” 40â€“45% RMSSD/SDRR error ecological)](https://arxiv.org/html/2510.06158v1)
- [Motion-artifact reduction for wrist PPG (Sensors/PMC review)](https://pmc.ncbi.nlm.nih.gov/articles/PMC6387309/)

