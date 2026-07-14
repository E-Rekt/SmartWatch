# CZProbe results — Citizen CZ Smart Gen 1

Captured 2026-07-13 over Wi-Fi ADB (`192.168.0.122:5555`), CZProbe running
on-watch with guided physical input (each control exercised in isolation on
instruction, capture pulled between steps).

**Evidence provenance:** [probe-raw-logcat.txt](probe-raw-logcat.txt) holds the
final session verbatim — the full startup dump plus the 138-event rotary
capture. The pusher/crown key-event evidence quoted below came from earlier
guided sessions the same day whose logcat buffers were subsequently cleared;
those lines are reproduced here exactly as captured.

## The brief's questions, answered

| Question | Answer |
|---|---|
| Exact API level | **28** (Android 9), security patch 2022-03-01 — measured |
| Exact Wear OS version | **Wear OS by Google 2.66**, Play services 25.14 — measured. **Overrides the brief**, which recorded 2.45 as confirmed on-device; the watch has since updated. |
| Do the pushers emit STEM_1/STEM_2? | **Yes.** Top → `KEYCODE_STEM_1` (via `gpio-keys`), bottom → `KEYCODE_STEM_2` (via `qpnp_pon`). Clean DOWN/UP pairs. Hold-repeat confirmed on the bottom pusher (repeat=1,2 at ~600 ms hold); top pusher was only tapped, hold untested. |
| Does the crown click reach us as STEM_PRIMARY? | **No — the system eats it.** Crown click acts as HOME and backgrounds the app (observed twice: focus moved to `HomeActivity2`, no key event of any code dispatched to the app). **Never design anything on crown click.** |
| Is the rotary encoder real, and its behavior? | **Yes.** Smooth (no detents per feel), signed direction, fixed quantum **±0.035** per `AXIS_SCROLL` event (device `bg-spi`). Sustained bursts measured at ~9–13 events/sec (peak 1-second window ~15, min inter-event gap 56 ms). System scroll factor 128 px/unit → ~4.5 px/event by default. |
| How hard can we push Compose on 1 GB? | **memoryClass 96 MB, largeMemoryClass 128 MB, totalMem 948 MB, `lowRamDevice = true`** — measured. |
| Real sensor list | See below — richer than the marketing list, with one negative surprise (NFC). |
| Exact px/dp/density/roundness | **416×416 px, 208×208 dp, density 2.0 (320 dpi), round=true** — measured. |

## Identity

Measured: `MANUFACTURER=Fossil`, `MODEL=CZ Smart`, device/board/product
`triggerfish`, fingerprint
`fossil/triggerfish/triggerfish:9/PXE2.201012.042/8611427:user/release-keys`.
The watch is Fossil-built. (The "P990MV-01" SKU, Snapdragon Wear 3100 SoC, and
AMOLED panel are external spec-sheet attributions, not probe measurements —
the probe found no chipset/panel data to confirm or deny them.)

## Input — measured event log

From the guided isolation test (one control at a time, on instruction):

```
02:33:51.165 KEY DOWN  code=265 (KEYCODE_STEM_1) repeat=0 dev=gpio-keys   ← top pusher tap
02:33:51.332 KEY UP    code=265 (KEYCODE_STEM_1)
02:33:58.524 KEY DOWN  code=266 (KEYCODE_STEM_2) repeat=0 dev=qpnp_pon    ← bottom pusher tap
02:33:58.702 KEY UP    code=266 (KEYCODE_STEM_2)
```

Held bottom pusher (earlier free-form session): `repeat=1` at +385 ms,
`repeat=2` at +492 ms — key repeat works, at least on STEM_2.

Rotary, from the guided direction test (user rotated away-from-wearer first,
then toward; full capture in the raw file):

```
02:50:56–02:51:02   ~30 events  raw=-0.035  (away phase)
02:51:02–02:51:16  ~105 events  raw=+0.035  (toward phase)
```

Away-from-wearer → negative `AXIS_SCROLL` (scrolls content down under the
standard `-raw × factor` convention). Magnitude never varies — rotation speed
is encoded purely in event rate.

### Rotary dispatch gotcha (cost us four capture attempts)

Since API 26 a **focused scrollable View consumes rotary events natively** —
`Activity.onGenericMotionEvent` only sees the leftovers when the view is
pinned at a scroll edge. Anything that needs to observe or customize crown
input must hook **`dispatchGenericMotionEvent`**. The committed
`MainActivity.kt` does this; note the probe source in the brief's appendix
predates the fix and still shows the broken `onGenericMotionEvent` version —
**the repo code is authoritative, not the appendix**.

## Memory → stack decision

`lowRamDevice = true` with a 96 MB per-app heap (measured). This is a strong
signal to go **Views-first**: `lowRamDevice` is the platform's own flag that
heavy runtimes aren't welcome, and Compose for Wear adds runtime weight and
allocation churn on top of a 96 MB heap. Recommendation for build step 3:
plain Views; if Compose is still tempting, time-box a benchmark spike knowing
the data leans no.

## Display

416×416 round, 208 dp square viewport, density 2.0 (measured). The inscribed
square of the circle is ~294 px — corner content clips, keep primary UI inside
generous insets.

## Sensors (as enumerated by SensorManager)

- **HR/PPG**: PixArt PAH8131 PPG; LifeQ algorithm suite (heart rate, heart beat, RR intervals, daily/basal RHR, calories, and `lifeq_lel_ott`); PixArt PAH8011 off-body detect
- **Motion**: STMicro LSM6DSO accel + gyro (plus uncalibrated variants), Qualcomm fusion (rotation vector, game RV, geomag RV, linear accel, gravity, motion/stationary detect, wrist tilt)
- **Environment**: TDK-InvenSense ICP-101xx barometer, AKM AK0991x magnetometer, Lite-On LTR-308 ambient light
- **Activity**: Fossil pedometer (types 18/19), significant motion
- **Features**: GPS **yes**, microphone **yes**, BLE **yes**, Wi-Fi **yes**, telephony **no** — and **NFC no**, which **contradicts the brief's caseback-confirmed hardware list**; `android.hardware.nfc=false` is what the OS reports, so no payments and no NFC-based features.

## Platform gotchas discovered during probing

1. **Aggressive backgrounding.** Repeatedly, within the ≤30–60 s between test
   instructions, the foreground probe lost focus and the watchface
   (`HomeActivity2`) was resumed — even with "Stay awake while charging"
   enabled per the setup docs (the watch may not have been on the charger
   throughout; not isolated further). `adb shell svc power stayon true`
   **stopped the homing entirely** — use it for every test/dev session (and
   `stayon false` after). The real app must expect constant pause/resume and
   consider ambient support for anything glanceable (timers!).
2. **Crown click = HOME.** It is the app-exit gesture; apps never see it.
3. **The watch clock is months wrong** (logcat timestamps read April while the
   real date is July). Standalone/unpaired Wear OS 2 did not sync time.
   **For a reminders/timers app this is existential** — step 2 must include a
   time-sync strategy (verify `auto_time` over Wi-Fi/NTP, surface clock drift
   in-app, or sync against a network clock when connected).
4. **Raw input nodes are locked** (`getevent` permission-denied for shell) —
   production Fossil build; app-layer instrumentation is the only window.
5. Default rotary scroll speed (~4.5 px/event) is leisurely on a 416 px
   screen. For list navigation, treat ticks as discrete item-steps or apply a
   3–5× multiplier rather than relying on the native factor.

## Verdict

Toolchain proven (build → Wi-Fi ADB deploy → run → instrument, end to end).
Hardware characterized. **Step 1 complete.** Next: step 2 — Room schema
(Task / Reminder / TimerPreset) + repository, designed for Views-first UI and
with the time-sync question answered first.
