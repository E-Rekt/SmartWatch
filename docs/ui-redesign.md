# CZTask UI Redesign â€” "Green Hill Time" everywhere
**Design spec v1.0 â€” every screen, interaction, and motion, concrete enough to build in Views/canvas on API 28.**

Sources honored: `docs/probe-results.md` (all geometry/input numbers below are the measured ones), `docs/data-layer-design.md` (no schema changes required except where explicitly flagged to a documented cut-list re-entry path), `ui/ListKit.kt` and `face/CzWatchFaceService.kt` (extended, not replaced).

---

## 0. Design principles (the filter every pixel passed through)

1. **One glance = one answer.** Every screen answers exactly one question. The home screen's question is *"what should I be doing right now?"* â€” nothing else is allowed on it.
2. **Zero-decision primary paths.** The most important actions (capture a thought, start working, mark done) are one physical press or one tap. Choices are for secondary paths only.
3. **Make time physical.** Time-blindness is fought with countdowns, depleting arcs, and relative phrasing ("IN 12 MIN"), never with abstract clock math the user must do in his head.
4. **Color is a language, not decoration.** Gold = task/actionable. Blue = checkpoint (reminder). Red = act *now* (one meaning, never diluted). Green = success flash only. Everything else is white/gray on black.
5. **Motion is feedback or orientation. Never idle.** If nothing changed and nothing is running, zero pixels move. Animations are pixel-quantized (4â€“6 discrete frames) â€” on-brand for a 16-bit HUD and cheap on a Wear 3100.
6. **The pixel font is chrome, not content.** Press Start 2P renders times, counts, and fixed HUD words. User-entered text is always the system sans â€” legibility beats theme, and this is what keeps the app from becoming a toy.

---

## 1. Visual language

### 1.1 Palette (canonical tokens â€” most already exist in `CzWatchFaceService`)

| Token | Hex | Meaning / allowed uses |
|---|---|---|
| `BLACK` | `#000000` | Background, everywhere, always (AMOLED) |
| `VALUE_WHITE` | `#F8F8F8` | Primary content: titles, hero numbers |
| `HUD_YELLOWâ†’HUD_ORANGE` | `#F8E038 â†’ #F09018` | Vertical gradient, **HUD labels only** (NOW, TASKS, TIME UPâ€¦) |
| `RING_GOLD` | `#F0C018` | Task glyphs, counts, timer arc, primary buttons, GO |
| `RING_GOLD_DARK` | `#9A7A00` | Ring inner edge, pressed-state fill |
| `TRACK_GOLD` | `#3A2E08` | Depleted arc track, inactive-gold outlines |
| `CHECKPOINT_BLUE` | `#2A5FC4` | Reminder star-post ball only |
| `GRASS_GREEN` | `#3F8B0F` | Success confirmation flash only (â‰¤200 ms) + checker lip |
| `PANIC_RED` | `#E23D28` | "Act in the next seconds": final 10 s, overdue checkpoint, TIME UP, delete-arm |
| `DATE_GRAY` | `#C0C0C0` | Secondary text |
| `DIM_GRAY` | `#9E9E9E` | Tertiary text, hints, empty states |
| `AMBIENT_GRAY` | `#909090` | Ambient face only |
| `DIRT_LIGHT / DIRT_DARK` | `#6B4520 / #4A2F14` | Checker footer only |
| `SURFACE` | `#141414` | The only non-black fill (tile button). **Replaces `#203048`** â€” the slate blue in the tile is off-palette; kill it. |

Disabled/done state = the element's normal color at **45% alpha** (matches existing `dimTitle`).

### 1.2 Typography

Press Start 2P is built on an 8 px grid; sizes must be multiples of 8 px to stay crisp. Density is 2.0, so **sp values are pxÃ·2** (canvas code uses px, View code uses sp).

| Role | Font | Canvas px | View sp | Case |
|---|---|---|---|---|
| Micro label / hints | Press Start 2P | 16 | 8 | CAPS |
| Header / HUD label / small numbers | Press Start 2P | 24 | 12 | CAPS |
| Row numbers, buttons, counts | Press Start 2P | 32 | 16 | CAPS |
| Hero time / countdown / TIME UP | Press Start 2P | 48 | 24 | â€” |
| Row subtitle | sans (DeviceDefault) | â€” | 12 | as typed |
| Row title | sans | â€” | 16 | as typed |
| Featured task (NOW) | sans **bold** | 40 (20 sp) | 20 | as typed |
| Alert title | sans bold | 44 (22 sp) | 22 | as typed |

Hard shadow: every pixel-font string drawn on canvas gets the existing 3 px (2 px for 16â€“24 px sizes) hard black offset shadow â€” never blur. Width check: Press Start 2P is monospace 1 em/char; keep strings â‰¤ `chordWidth(y)/size` chars (e.g. "25:00" at 48 px = 240 px, fits everywhere inside the 294 px inscribed square).

### 1.3 Glyph vocabulary (new `ui/Glyphs.kt` â€” shared canvas drawables)

Port the face's private `drawRing / drawSparkle / drawStarPost` into a shared `Glyphs` object (parameterized by center + scale) so the face, lists, NOW card, and alerts render **one** icon vocabulary. All are code-drawn; no bitmaps except the cached checker.

| Glyph | Construction (at 26Ã—26 dp row box; face keeps its px sizes) | States |
|---|---|---|
| **Ring** (open task) | Stroke circle r 10 px: 6 px `RING_GOLD_DARK` under 4 px `RING_GOLD`, white glint dot upper-left | â€” |
| **Sparkle** (done task / all-clear) | 4-ray star, 3 px strokes; `RING_GOLD` for all-clear, `DIM_GRAY` @45% for a done row | â€” |
| **Star post** (checkpoint) | 3 px gray pole + ball r 5 px + glint | Ball `CHECKPOINT_BLUE` = enabled; hollow `DIM_GRAY` outline = disabled; `PANIC_RED` = fired-unacknowledged |
| **Arc-clock** (timer preset) | Stroke circle r 10 px `TRACK_GOLD` with 270Â° `RING_GOLD` arc over it | â€” |
| **Stop** | 12Ã—12 px filled square `RING_GOLD` | â€” |
| **Plus** | Pixel plus, 3 px strokes, `RING_GOLD` @70% | â€” |

**Removal:** all text/emoji glyphs (`â—‹ âœ“ â— â—Œ â± â– `) â€” they render inconsistently and off-brand. `Row.Item.glyph: String` becomes `glyph: GlyphKind` rendered by a small custom `GlyphView` (one `onDraw`, no allocation per frame).

### 1.4 Grid & safe areas (416Ã—416 round, measured)

- Inscribed square â‰ˆ 294 px â€” all multi-line text blocks stay within **x âˆˆ [64, 352]** (width â‰¤ 288 px) between y 96â€“320.
- Full-width elements (arcs, checker footer, pills below y 240) may use the chord: `chord(y) = 2Â·âˆš(208Â² âˆ’ (208âˆ’y)Â²)`.
- Bezel arc: inset 14 px, stroke 10 px (existing `arcRect` â€” reuse everywhere an arc appears).
- Checker footer: 416Ã—64 px, drawn from y 352 (existing cached bitmap â€” share the instance).
- Row tap targets: raise `minHeight` 44 dp â†’ **48 dp** (96 px) in `row_two_line.xml` / `row_center_text.xml`.

---

## 2. Interaction model

### 2.1 Global input map (from the measured probe: STEM_1=top, STEM_2=bottom w/ hold-repeat, crown click = HOME and unusable, rotary Â±0.035/event @ 9â€“13 ev/s)

| Input | Anywhere in app | Notes |
|---|---|---|
| **STEM_1 (top) tap** | **Capture** â†’ `QuickTaskActivity` | The sacred button. Press â†’ speak â†’ done. Never rebind. System hardware-button binding covers capture from the face/anywhere too. |
| **STEM_2 (bottom) tap** | Open Timers (hero if running) | "Time button" |
| **STEM_2 hold â‰¥600 ms** | **Instant focus timer**: starts last-used focus duration (default 25:00) bound to the featured NOW task; 50 ms buzz + GO wipe | Hold-repeat is measured-reliable on STEM_2 only. Zero-decision timeboxing. |
| **Crown rotate** | Scroll lists / change dial value | See 2.2 tuning |
| **Crown click** | HOME (system eats it) | Design nothing on it, ever |
| **Tap** | The affirmative: toggle done, start preset, confirm, select | Feedback < 100 ms always |
| **Long-press** | Secondary/destructive: delete-arm, NOT NOW, cancel running timer | Never the only path to anything frequent |
| **Swipe right** | System back/dismiss | Never overridden |

### 2.2 Rotary tuning (`RotaryStepper` helper in ListKit; hook `dispatchGenericMotionEvent` per the probe gotcha)

- Accumulate raw `AXIS_SCROLL`; every **|0.105|** (= 3 quanta) â†’ one **row-step** (`smoothScrollBy` one row height, 250 ms). Reset accumulator after 300 ms idle.
- Fast mode: 5 consecutive inter-event gaps < 70 ms â†’ 2 rows/step. (Native ~4.5 px/event is measured-too-slow; item-stepping is the probe's own recommendation.)
- Direction: keep the native convention (away-from-wearer = content down).
- TimeDial: 1 step = +5 min (fast = 15 min); duration mode = 1 min below 10:00, 5 min above.

### 2.3 Haptics (API 28 `VibrationEffect` waveforms, ms)

| Event | Pattern | Rationale |
|---|---|---|
| Capture saved | 40 | "Got it" â€” trust the button without looking |
| Task done | [30, 60, 30] | Small reward |
| ALL CLEAR (last task) | [30, 60, 30, 60, 120] | Bigger reward, distinct |
| Timer start / GO | 50 | Commitment signal |
| NOT NOW defer / delete armed | 20 | Tick |
| Checkpoint fired | channel default (long) | Existing |
| Timer done | system sound + vibrate | Existing |
| **Panic final 10 s** | **none** | Visual only â€” buzzing here trains alarm fatigue |

---

## 3. Screens

### 3.0 NOW â€” the new home (rebuilt `MainActivity`)

**Question answered: "What should I be doing right now?"** The 3-row menu stops being the front door; it moves below the fold of one screen.

Structure: one `WearableRecyclerView`, **edge-centering OFF**, rows = `[Row.Hero(NowCardView, 416Ã—416 full-bleed)] + menu rows`. Scroll snaps back to the hero on every `onResume` (the process dies in 30â€“60 s anyway â€” resume is the common case; always land on NOW).

**NowCardView (canvas, px):**
- y 54 â€” current time `HH:MM`, pixel 24 px `DATE_GRAY`, centered. Time stays visible inside the app: anti-time-blindness. If `ClockStatus != OK`, replaced by `! CLOCK` pixel 24 px `PANIC_RED` (existential per data-layer doc).
- y 96 â€” HUD label `NOW`, pixel 24 px, yellowâ†’orange gradient + hard shadow.
- **Center block, by state (priority: A > B > C > D):**
  - **A â€” timer running:** bezel arc (inset 14, track+gold, red â‰¤10 s) mirroring the face; countdown pixel **48 px** `VALUE_WHITE` at y 205; bound task label sans 14 sp `DATE_GRAY`, 1 line â‰¤280 px, y 245. Tap card â†’ Timers hero.
  - **B â€” overdue checkpoint (fired, unacknowledged):** red-ball star post 24 px at y 140; label sans bold 20 sp `VALUE_WHITE`, â‰¤2 lines, centered y ~190; pill `DONE` 160Ã—56 px at y 272: black fill, 2 px `PANIC_RED` border, pixel 32 px text. Tap = acknowledge (cancel notif, mark linked task done if any).
  - **C â€” default:** the **featured task** = *oldest* open task (new DAO query `ORDER BY created_at ASC LIMIT 1` â€” do the thing you captured first). Title sans bold 20 sp `VALUE_WHITE`, â‰¤3 lines (40 px line height), block y 132â€“252, width â‰¤288 px. Pill **`â–¶ GO 25:00`** 176Ã—56 px at y 272: black fill, 2 px `RING_GOLD` border, 4 px corner radius, pixel 32 px `RING_GOLD`. Tap pill = start focus timer immediately (last-used focus duration) bound to this task. Long-press pill = duration TimeDial. Long-press *title* = **NOT NOW**: sets `createdAt = now` (zero-migration defer â€” task drops to the back of the NOW rotation), card slides left 4 quantized frames/150 ms, 20 ms tick, next-oldest task appears.
  - **D â€” all clear:** gold sparkle (r 22 px) y 150; `ALL CLEAR` pixel 32 px `RING_GOLD` y 205; below y 245: next checkpoint as star-post 16 px + `08:00 MEDS` sans 14 sp `DATE_GRAY`, or if none: `TOP BUTTON + TASK` sans 12 sp `DIM_GRAY`.
- y 352+ â€” checker footer (shared bitmap with the face). No scroll hints, no chevrons â€” the crown affordance is learned once.

**Menu rows below the hero** (48 dp `Row.Item`, glanceable status baked in):
- Ring glyph â€” `Tasks` â€” subtitle `3 open` / `all clear`
- Star-post glyph â€” `Checkpoints` â€” subtitle `next 08:00 meds` / `in 12 min meds` (white when â‰¤60 min) / `none`
- Arc-clock glyph â€” `Timers` â€” subtitle `18:24 left` (white, live while visible) / `3 presets`

### 3.1 Tasks (`TasksActivity` restyle)

- Row 0: header `Row.Header` â€” `TASKS Ã—3` pixel 12 sp, "TASKS" in gradient, count in `RING_GOLD`. 40 dp tall, non-tappable.
- Task rows: Ring glyph gold + title sans 16 sp white. **No subtitles** (noise). Done rows: dim sparkle glyph, title @45%, sink below opens (existing ORDER BY already does this).
- **Tap = toggle done â†’ RING COLLECT animation** (see Â§5) + [30,60,30] buzz. If it was the last open task â†’ ALL CLEAR overlay (600 ms one-shot, gold sparkle burst + `ALL CLEAR` pixel 32 px).
- **Long-press = delete-arm** (replaces instant delete â€” accidental destruction of externalized memory breaks the whole trust contract): row content swaps to `DELETE?` pixel 12 sp `PANIC_RED` + dimmed title; tap within 3 s confirms (row collapses in 4 frames/200 ms, 40 ms buzz); timeout or scroll reverts. One gesture + one tap; still fast, no dialog.
- Last rows: `+ ADD` Center row (`Plus` styling, `RING_GOLD` @70%) â†’ existing text input. `CLEAR DONE` Center row, dim, appears only when â‰¥3 done rows exist (uses existing `clearDone()`; zero-migration hygiene â€” auto-purge needs `doneAt`, which stays on the documented cut list).
- Empty state: dim ring outline (r 20 px, `TRACK_GOLD` stroke) + `NO TASKS` pixel 12 sp `DIM_GRAY` + `TOP BUTTON ADDS ONE` sans 12 sp `DIM_GRAY`. The empty state is the tutorial.

### 3.2 Checkpoints (`RemindersActivity` restyle â€” renamed "Checkpoints" everywhere the user sees)

- Header: `CHECKPOINTS` pixel 12 sp gradient (11 chars Ã— 24 px = 264 px, fits).
- Rows: star-post glyph (blue=on / hollow gray=off / red=fired-unacked) + title sans 16 sp + subtitle sans 12 sp: `Daily 08:00 Â· next 08:00` â€” **when next fire â‰¤60 min, the "next" clause swaps to relative** `Â· in 12 min` **and turns `VALUE_WHITE`** (time-blindness: relative beats absolute inside the hour).
- Tap = toggle enabled (glyph swap, 40 ms buzz). Long-press = delete-arm (same pattern as Tasks).
- `+ ADD` row at end. **Add flow shrinks 4 â†’ 3 steps:** text input â†’ repeat picker (Daily / Weekdays / Weekends / Today / Tomorrow â€” unchanged) â†’ **TimeDial** (one screen replaces hour picker + minute picker).
- Empty: gray star post + `NO CHECKPOINTS` pixel 12 sp dim.

### 3.3 Timers (`TimersActivity` restyle)

Same "hero + rows" pattern as NOW (one recycler, centering off when hero present):

- **Running hero (full-bleed 416 px canvas card):** bezel arc inset 14 (track `TRACK_GOLD`, remaining `RING_GOLD`, `PANIC_RED` â‰¤10 s with 1 Hz digit flash â€” port the face's drowning-countdown rules verbatim); countdown pixel **48 px** center y 200; bound task label sans 14 sp `DATE_GRAY` y 240. **Long-press anywhere â‰¥600 ms = cancel** (arc collapses 4 frames/200 ms + 50 ms buzz). A single tap shows `HOLD TO STOP` pixel 16 px `DIM_GRAY` at y 330 for 2 s (teach on demand, don't display permanently). *Removes tap-to-cancel â€” a running timebox must not die to a sleeve tap.*
- Preset rows: arc-clock glyph + duration in **pixel 16 sp `RING_GOLD`** (`25:00`) as title; user label (if any) as sans subtitle. Tap = start: GO wipe (arc draws 0â†’360Â° clockwise, 400 ms) then hero state; 50 ms buzz. Long-press = delete-arm.
- `+ ADD` row â†’ TimeDial in duration mode (1â€“90 min). *Removes the 11-row minutes `PickerActivity` list.*
- **First-run seed: 25:00, 10:00, 5:00** â€” the app is useful before any setup, and "empty timers" ceases to exist as a state.

### 3.4 TimeDial â€” new single-screen picker (`TimeDialActivity`, canvas)

Replaces the hour+minute two-screen flow; also serves duration entry.

- y 80: mode label pixel 24 px gradient â€” `WHEN` / `HOW LONG`.
- Center y 210: value `HH:MM` pixel **48 px** `VALUE_WHITE` (duration: `MM:SS`).
- y 250: relative echo sans 12 sp `DATE_GRAY` â€” `in 3 h 40 min` (time mode only; the anti-time-blindness line: the user never mentally subtracts).
- Static gold chevrons â–²â–¼ (8 px, 30% alpha) above/below the value â€” crown affordance, not animated.
- Crown: Â±5 min/step, fast-rotate Â±15 (per Â§2.2); wraps at midnight. Haptic 20 ms only when crossing an hour boundary.
- Default value: next quarter-hour at least 10 min ahead (fewest rotations for the common case).
- **Tap the value (center circle r 120 px) = confirm**: value flashes `GRASS_GREEN` for 2 frames/100 ms, finish with result. Swipe right = cancel (system back).

### 3.5 Quick capture (`QuickTaskActivity`)

Flow unchanged (launcher-visible, STEM-bound, RemoteInput voice). Two changes:
- **Remove the Toast.** On success, show a 500 ms full-screen confirm frame: black, mini RING COLLECT animation (5 frames) at y 170, captured title sans 16 sp white â‰¤2 lines below, then `finish()`. 40 ms buzz. The confirmation *is* the reward loop for capturing instead of holding thoughts in working memory.
- Empty/cancelled result: silent finish, no error UI.

### 3.6 Alerts â€” new full-screen surfaces (checkpoint fired / timer done)

Notifications remain the durable layer (process-death-proof); the full-screen activity is the immediate layer. `Notifications.postReminder` adds `setFullScreenIntent` (CH_REMINDERS is already IMPORTANCE_HIGH) â†’ `AlertActivity`.

**Checkpoint fired:** black bg; blue star post 32 px y 120; label sans bold 22 sp `VALUE_WHITE` â‰¤3 lines centered ~y 200; scheduled `HH:MM` pixel 24 px `DATE_GRAY` beneath; pill `DONE` 200Ã—56 px y 300 (gold border/text, pixel 32 px). Tap DONE = cancel notif + mark linked task done (if `taskId != null`). Long buzz on entry (channel). No auto-dismiss â€” it must nag until acted on; if the activity dies (30â€“60 s kill), the notification still stands.

**Timer done (same layout, re-skinned):** gold ring icon y 120; `TIME UP` pixel **48 px** `PANIC_RED` y 200 (1 Hz red/white flash for the first 10 s, then solid red); bound task sans 16 sp below; `DONE` pill. Purpose: the end of a timebox is the highest-leverage re-engagement moment â€” it must be unmissable, not a dismissible peek.

**Deferred (v2, uses the documented cut-list re-entry `snooze` nullable column):** `+10 MIN` secondary pill below the fold on both alerts. Do not fake it with in-memory state â€” process death would eat it.

### 3.7 Generic picker (`PickerActivity` â€” repeat choices etc.)

Keep as-is structurally; restyle: title row becomes a pixel 12 sp gradient header; add `LinearSnapHelper` so a row is always centered; crown steps one row (Â§2.2). Selected-on-tap flash: row text `GRASS_GREEN` 2 frames/100 ms before finish.

---

## 4. Watch face v2 (`CzWatchFaceService` â€” additive changes only)

The face already nails the language. Six changes:

1. **Checkpoint proximity swap:** when next fire â‰¤20 min, the checkpoint line changes from `08:00 MEDS` to **`IN 12 MIN MEDS`**; line color `DATE_GRAY` normally, `VALUE_WHITE` when â‰¤20 min. Updates on the existing per-minute `onTimeTick` â€” zero extra cost, and it's the single biggest anti-time-blindness feature on the watch.
2. **Arc tip glint:** while a timer runs (per-second invalidate already exists), draw a 3 px `VALUE_WHITE` dot at the arc head on even seconds. Motion cue for where to look; costs nothing extra.
3. **All-clear one-shot:** engine caches last `openTasks`; when a refresh observes >0 â†’ 0 while visible, play the sparkle burst once â€” 6 frames / 600 ms (10 fps), rays expanding from the ring icon. Never loops, never replays until the count rises and returns to 0.
4. **Minute pulse (interactive only):** on minute change, time digits drop 2 px for one frame and return (2 invalidates, 100 ms apart). Purpose: the passage of a minute becomes *perceptible*. Cost: 2 frames/min. Ship behind a single boolean default-ON.
5. **Clock-guard dot:** `ClockStatus != OK` â†’ pixel `!` 16 px `PANIC_RED` right of the date. The measured 15-month clock incident earns a permanent face-level tell.
6. **Tap zones simplified:** the current `y > 200` rule becomes â€” timer running: any tap â†’ Timers hero; otherwise: any tap â†’ NOW. One zone, no misfires. *(Remove the multi-zone idea entirely; fewer targets = fewer wrong launches.)*

**Ambient:** keep gray time/date + burn-in jitter exactly as built; add one line â€” next checkpoint `HH:MM` pixel 16 px `#606060` at y 232 (a glance at a sleeping watch still answers "when is the next thing"). No arc, no rings, no checker, AA off in low-bit â€” unchanged.

**Face battery rules (hard):** interactive baseline = 1 invalidate/min; 1 Hz only while a timer is visible and running (existing); one-shots â‰¤600 ms at â‰¤10 fps quantized; minute pulse = 2 frames; ambient = platform ticks only; **no `ValueAnimator`/choreographer loops in the face, ever** â€” all animation is stepped off the existing `Handler`.

---

## 5. Motion design â€” master table

Implementation rule: **pixel-quantized motion** â€” 4â€“6 discrete keyframes stepped by `Handler.postDelayed`, no interpolators, no smooth tweens. It reads as 16-bit and it is the cheapest possible animation on this SoC. Activity transitions: `overridePendingTransition(0, 0)` everywhere â€” instant cuts are the brand; games cut, they don't crossfade.

| Name | Trigger | Duration / frames | Where | Purpose |
|---|---|---|---|---|
| RING COLLECT | task marked done | 300 ms / 5 frames: ring scales 1â†’1.3, swaps to sparkle, settles dim | Tasks row glyph, QuickTask confirm | Reward â€” the dopamine tick that makes checking off feel like collecting a ring |
| ALL CLEAR burst | last open task done | 600 ms / 6 frames, gold rays + `ALL CLEAR` 32 px | Tasks overlay, face (Â§4.3) | Reward, session-complete |
| GO wipe | timer start | 400 ms / 8 frames, arc draws 0â†’360Â° clockwise | Timers hero, NOW state A entry | Orientation â€” "the ring is armed" |
| PANIC flash | timer â‰¤10 s; TIME UP first 10 s | 1 Hz square wave (redâ‡„white digits), never faster, settles solid red after 30 s | Face, Timers hero, NOW, alert | Urgency â€” red's only job |
| Delete-arm swap | long-press row | instant swap, 3 s timeout revert | Tasks/Checkpoints/Timers rows | Safety without a dialog |
| Confirm collapse | delete confirmed | 200 ms / 4 frames row shrink | rows | Feedback |
| NOT NOW slide | long-press NOW title | 150 ms / 4 frames slide-left | NOW card | Feedback + "it moved back, not away" |
| Green confirm flash | dial/picker confirm | 100 ms / 2 frames `GRASS_GREEN` | TimeDial, pickers | Feedback |
| Minute pulse | minute change | 200 ms / 2 frames, 2 px dip | Face interactive | Make time passing visible |
| Arc tip glint | each second, timer running | 1 fps toggle | Face, hero | Attention anchor |

**Forbidden:** looping/idle animation of any kind, parallax, breathing effects, animated empty states, scroll physics flourishes, transition animations.

---

## 6. Tile v2 (`CzTileService` â€” light touch; tiles 1.1 can't load custom fonts)

- Remove the `no checkpoint` gray filler â€” render nothing when there is nothing (less is the point).
- Mirror the â‰¤20 min relative swap: `IN 12 MIN MEDS`.
- `ALL CLEAR` in `RING_GOLD` when count is 0 (matches face/NOW vocabulary).
- Button bg `#203048` â†’ `SURFACE #141414`, text stays `RING_GOLD`. All caps everywhere approximates the HUD voice since Press Start 2P is unavailable in tiles.

---

## 7. What is REMOVED (explicit)

1. **Menu-as-home** â€” the 3-row menu screen. NOW is the home; the menu is three status rows below the fold of the same screen.
2. **`+ Add task` as the first row** of Tasks â€” moved to the end. The first thing seen must be the next task, not an input affordance; STEM_1 is the capture path and the empty state teaches it.
3. **Toast confirmations** (QuickTask) â€” replaced by the 500 ms ring-collect confirm frame.
4. **Emoji/text glyphs** `â—‹ âœ“ â— â—Œ â± â– ` â€” replaced by the drawn glyph vocabulary.
5. **Two-screen hour â†’ minute pickers** â€” replaced by TimeDial (one screen, crown-driven, relative echo).
6. **Tap-to-cancel on a running timer** â€” replaced by hold-to-stop with on-demand hint.
7. **Instant long-press delete** â€” replaced by delete-arm (3 s red confirm). Adds one tap; protects the externalized-memory trust contract.
8. **`running â€” tap to cancel` subtitle** â€” gone with it.
9. **Tile `no checkpoint` placeholder** â€” gone.
10. **Off-palette slate blue `#203048`** â€” replaced by `#141414`.
11. **Default activity transitions** â€” zeroed; instant cuts.
12. **Multi-purpose face tap zones** (`y > 200`) â€” one zone, state-routed.
13. **Clock warning as a tappable-looking menu row** â€” becomes the NOW header strip + face `!` dot (visible exactly where the user actually looks).

---

## 8. Implementation map (respecting existing code)

| Piece | Change |
|---|---|
| `ui/Glyphs.kt` **new** | Shared canvas glyphs (port face's `drawRing/drawSparkle/drawStarPost`, add arc-clock/stop/plus); `GlyphView` for rows |
| `ui/ListKit.kt` | Add `Row.Header(text)`, `Row.Hero(onBind)` view types; `Row.Item.glyph: GlyphKind`; `RotaryStepper` (via `dispatchGenericMotionEvent` â€” the probe-mandated hook); `setUpWearList(centering: Boolean)`; delete-arm state handling in `RowAdapter` |
| `row_*.xml` | `minHeight` 48 dp; glyph `TextView` â†’ `GlyphView` |
| `ui/MainActivity.kt` | Becomes NOW: hero card (`NowCardView` canvas) + menu rows; STEM_2 hold detection (`onKeyDown` repeat, measured working); featured-task query `ORDER BY created_at ASC LIMIT 1`; NOT NOW = touch `createdAt` (zero migration) |
| `ui/TasksActivity.kt` | Header row, glyphs, ring-collect anim, delete-arm, `+ ADD`/`CLEAR DONE` at end, empty state |
| `ui/RemindersActivity.kt` | "Checkpoints" strings, glyph states, relative-time subtitle, 3-step add flow ending in TimeDial |
| `ui/TimersActivity.kt` | Hero running card, hold-to-stop, preset seed (25/10/5) on first run, TimeDial for durations |
| `ui/TimeDialActivity.kt` **new** | Canvas dial per Â§3.4 |
| `ui/AlertActivity.kt` **new** | Checkpoint-fired + timer-done skins; wired via `setFullScreenIntent` in `Notifications` |
| `ui/QuickTaskActivity.kt` | Confirm frame instead of Toast |
| `face/CzWatchFaceService.kt` | Â§4 items 1â€“6 (all additive; per-second ticker and ambient path unchanged) |
| `tile/CzTileService.kt` | Â§6 |
| `TimerStateStore` | Add optional `label: String` field (SharedPreferences â€” no migration) so hero/notification/face can show the bound task |
| Deferred to v2 (documented cut-list re-entry) | `+10 MIN` snooze (nullable column), done-task auto-purge (`doneAt`) |

**Suggested build order:** Glyphs â†’ ListKit extensions + rotary stepper â†’ list restyles â†’ NOW â†’ TimeDial â†’ Timers hero â†’ QuickTask confirm â†’ Alerts â†’ face v2 â†’ tile v2. Each step ships independently; nothing blocks on schema work.

