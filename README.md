# SmartWatch

Standalone **Wear OS 2** productivity app for a **Citizen CZ Smart Gen 1**
(P990MV-01 · Snapdragon Wear 3100 · 1 GB RAM · 416×416 round AMOLED ·
Wear OS by Google 2.66 · Android 9 / **API 28** — as measured on-device, see
[`docs/probe-results.md`](docs/probe-results.md)).

Focus: task list, time-based reminders, timers/Pomodoro, and voice
quick-capture — no phone companion required. We are **not** replacing the OS
or firmware; the bootloader is locked and that path was rejected.

Full context, constraints, and the agreed build order live in
[`CLAUDE_CODE_BRIEF.md`](CLAUDE_CODE_BRIEF.md). Read it before touching
anything.

## Build config (decided — don't relitigate)

```
compileSdk = 34
minSdk     = 26
targetSdk  = 28   // matches the device
```

Kotlin, JVM target 17.

## Build order

1. **Toolchain + hardware probe** — [`CZProbe/`](CZProbe/) ✅ done, findings in [`docs/probe-results.md`](docs/probe-results.md)
2. Room schema (Task / Reminder / TimerPreset) + repository ✅ done — [`CZTask/`](CZTask/), design in [`docs/data-layer-design.md`](docs/data-layer-design.md); time-sync solved ([`docs/time-sync.md`](docs/time-sync.md))
3. **Views** shell ✅ done — 786 ms cold start, ~29 MB PSS on-device (Compose ruled out by measured lowRamDevice/96 MB heap)
4. Alarm + notification layer ✅ done — mutex-serialized reconcile, coalesced RTC alarm, boot/TZ/TIME_SET handling per the measured broadcast matrix, elapsed-axis timer engine; reboot-tested
5. Voice quick-capture + stem bindings ✅ done — `QuickTaskActivity` (launcher-visible for hardware-button binding), STEM_1 in-app
6. Tile ✅ done — third-party tiles verified supported on this 2.66 home; `CzTileService` (complication skipped: no host faces remain on the debloated watch)

**Beyond the plan:** reversible 21-package debloat, boot-launch into CZTask, and the
**"Green Hill Time"** Sonic-HUD watch face (`face/CzWatchFaceService.kt`) — time/date,
RINGS = open tasks, star-post checkpoint = next reminder, depleting gold bezel ring =
running timer, Press Start 2P (OFL).

## CZProbe (step 1)

Zero-dependency hardware probe. Build and deploy from `CZProbe/`:

```powershell
cd CZProbe
adb connect <watch-ip>:5555   # puck is power-only; Wi-Fi ADB is the only path
.\gradlew installDebug
adb logcat -c
adb logcat -s CZPROBE:V
```

Then press both pushers, click the crown, rotate it both ways, and capture
the logcat. See [`CZProbe/README.md`](CZProbe/README.md) for the full
on-watch setup steps and the gotchas (screen-sleep drops ADB, rotary needs
a focused view, auth prompt times out fast).
