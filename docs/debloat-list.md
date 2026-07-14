# Debloat manifest — Citizen CZ Smart Gen 1

Device-side state (not in any APK): 30 packages removed for user 0 across
three rounds, 2026-07-13. Goal: CZTask is effectively the only software.
Everything is reversible:

```
adb shell cmd package install-existing <package>
```

## Removed

Round 1 — obvious apps: `com.android.vending` (Play Store),
`com.google.android.apps.fitness`, `com.google.android.apps.translate`,
`com.google.android.apps.walletnfcrel`, `com.fossil.activationanalytic`,
`com.fossil.elabel`, `com.google.android.marvin.talkback`, `com.czprobe`.

Round 2 — deeper: `com.google.android.deskclock`,
`com.google.android.wearable.reminders`, `com.google.android.apps.handwriting.ime`,
`com.google.android.clockwork.gestures.tutorial`, `com.google.android.tts`,
`com.fossil.phone`, `com.fossil.wearable.pusher`,
`android.autoinstalls.config.google.wear`, `com.google.android.partnersetup`,
`com.fossil.wearables.ctz` (Citizen faces — replaced by ours),
`com.google.android.wearable.assistant`, `com.google.android.googlequicksearchbox`,
`com.google.android.clockwork.flashlight`.

Round 3 — hardware-orphans + telemetry + wellness layer: `com.android.nfc` +
`com.google.android.clockwork.nfc` (probe measured NO NFC hardware),
`com.qualcomm.qti.sidekickmetrics`, `com.cei.servicetool`,
`com.fossil.oemsetup.triggerfish`, `com.fossil.twm`,
`com.fossil.wearables.healthtracker` (wellness app/tile — CZTask reads raw
sensors itself), `com.fossil.wearables.batterysaver`, `com.fossil.hfpconnector`.

## Deliberately KEPT (load-bearing — do not remove)

- `android`, `com.android.shell` (adb!), `com.android.bluetooth`
- `com.google.android.wearable.app` (Home/launcher/RemoteInput incl. voice),
  `com.google.android.apps.wearable.systemui`, `...wearable.settings`
- `com.google.android.gms` + `com.google.android.gsf` (Play Services; NTP time)
- `com.google.android.inputmethod.latin` (Gboard — keyboard AND voice typing)
- `com.qualcomm.timeservice` (time sync — the 15-month-clock incident)
- `com.fossil.charge.triggerfish` (charging screen), `com.fossil.wearables.fsgattservice`,
  `com.fossil.wearos.sensoraccessservice` (PPG/LifeQ sensor stack — biometrics),
  `com.fossil.wearables.savedfaceservice`, `com.fossil.wearable.fspropertyutils`
- `com.google.android.buttons.triggerfish.ctz` (hardware-button remapping — Quick Task binding)
- `com.google.android.wearable.ambient`, `...batteryservices`, `...frameworkpackagestubs`,
  `com.google.android.clockwork.brightness`, setup wizard, providers, ext services

Verified after every round: Home renders, CZTask launches, keyboard + voice
input work, PPG/LifeQ sensors enumerate, charging screen appears on dock.
