# CZ Probe — Citizen CZ Smart Gen 1 (P990MV-01) hardware probe

Step 1 of the build. Proves the toolchain works and answers every open hardware
question in a single deploy, so we stop guessing and design against facts.

A Gradle wrapper (pinned to Gradle 8.7, matching AGP 8.5.2) is included — open
this folder directly in Android Studio or run `.\gradlew installDebug` from a
terminal. On first run the wrapper downloads its Gradle distribution and the
build pulls AGP/Kotlin from Google's Maven; both need normal internet access.
If Android Studio suggests upgrading AGP/Gradle, decline for now — prove the
deploy loop on known-good versions first.

Zero dependencies — plain `Activity`, `Theme.DeviceDefault`, no AndroidX, no Compose.
If this won't deploy, the problem is the toolchain, not the code.

---

## Getting it onto the watch (Windows)

There is no USB data path on this watch — the charging puck is power-only.
Everything goes over Wi-Fi ADB.

**On the watch**

1. Settings → System → About → tap **Build number** 7×.
2. Settings → **Developer options**:
   - enable **ADB debugging**
   - enable **Debug over Wi-Fi**
   - enable **Stay awake while charging** ← do not skip this, see gotchas
3. Settings → Connectivity → Wi-Fi: join the **same network as your PC**.
   On Wear OS 2, Wi-Fi is often set to "Automatic" and only connects when
   Bluetooth is out of range. Force it on.
4. Back in Developer options, **Debug over Wi-Fi** shows the watch IP and port
   (`5555`). If it doesn't, read the IP from Settings → Connectivity → Wi-Fi →
   your network → Advanced.

**On the PC**

```powershell
adb connect 192.168.x.x:5555     # accept the prompt ON THE WATCH
adb devices                      # should list the watch as "device"

.\gradlew installDebug           # or just Run from Android Studio
adb logcat -c
adb logcat -s CZPROBE:V
```

Launch **CZ Probe** on the watch, then:

1. Press the **top pusher**
2. Press the **bottom pusher**
3. **Click** the crown
4. **Rotate** the crown both ways

Paste the logcat output back to me.

---

## Gotchas that will eat an hour

- **The watch drops the ADB connection when the screen sleeps.** Keep it on the
  charger with "Stay awake while charging" on, or re-`adb connect` constantly.
- **`adb connect` fails silently** if the PC and watch are on different subnets
  (very common with guest/IoT SSIDs) or if Windows Firewall blocks it.
- **The authorization prompt appears on the tiny watch screen** and times out.
  Tap it fast, and tick "always allow".
- **Rotary events go to the focused view.** The probe calls `requestFocus()` on
  the ScrollView for exactly this reason. Forget it in the real app and you'll
  conclude the crown is broken.

---

## What this answers

| Question | Where it lands |
|---|---|
| Exact API level → does our stack fit? | `BUILD → SDK_INT` |
| Exact Wear OS version | `WEAR OS → Wear OS by Google` |
| Do the two pushers emit `STEM_1` / `STEM_2`? | `KEY DOWN` lines |
| Does the crown **click** reach us as `STEM_PRIMARY`, or does the system eat it? | `KEY DOWN` lines |
| Is the rotary encoder real, and what's the scroll factor? | `INPUT DEVICES` + `ROTARY` lines |
| How hard can we push Compose on 1 GB RAM? | `MEMORY → memoryClass`, `lowRamDevice` |
| Real sensor list (vs. the marketing list) | `SENSORS` |
| Exact px / dp / density / roundness | `DISPLAY` |

Once that comes back, we lock the stack and move to step 2 (Room schema).
