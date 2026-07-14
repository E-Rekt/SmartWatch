# Project brief: Wear OS productivity app for a Citizen CZ Smart Gen 1

You are picking this up cold. Everything you need is in this document. Read it fully before acting.

You're working with an **experienced Android developer on Windows**. The watch is **connected to the PC right now**.

---

## Long-term goal

Build a **standalone Wear OS app** for a Citizen CZ Smart Gen 1, to replace Citizen's poor companion app. Focus: **productivity** — task list, time-based reminders, timers/Pomodoro, and voice quick-capture. Standalone means no phone companion; the app must work with the watch alone.

**We are explicitly NOT replacing the OS or firmware.** The bootloader is locked, Qualcomm signed-boot is enforced, no custom ROM exists for this device, and no hardware drivers are published. That path was considered and rejected. Do not revisit it, and do not suggest rooting, unlocking, or flashing.

## The hardware (confirmed from the caseback)

- Citizen CZ Smart **Gen 1**, model **P990MV-01**, caliber P990, ref P990-S125359
- Qualcomm **Snapdragon Wear 3100**
- **~1 GB RAM**, 8 GB storage
- **416 × 416 round AMOLED**, 1.28"
- **A rotating + clicking crown** (rotary side button) plus **two separate pushers**
- Optical HR, accelerometer, gyroscope, barometer, magnetometer, GPS, NFC, mic, speaker
- 3ATM

## The software target (confirmed on-device)

- **Wear OS by Google 2.45**
- **System version: H MR2** → Android 9 Pie → **API 28**
- Google Play services **25.14.34** — a current 2025 build. Play-Services-backed APIs are NOT stale even though the OS is.
- Android security patch: **March 1, 2022**. The firmware is frozen and will never update.
- **Wear OS 2, never Wear OS 3.** Note there is no Wear OS built on API 29 at all — the platform jumps 28 → 30. Anything documented as "API 29+" is unavailable to us.

## Build config — already decided, don't relitigate

```
compileSdk = 34
minSdk     = 26      // rotary source constant + Tiles both need 26; device is 28 anyway
targetSdk  = 28      // matches the device; targeting higher buys nothing on an API 28 platform
```

Kotlin, JVM target 17.

## Consequences of API 28 — already worked out

- **Exact alarms are free.** `SCHEDULE_EXACT_ALARM` is an API 31 invention. On API 28, `AlarmManager.setExactAndAllowWhileIdle()` fires with no runtime permission and no user grant flow. The reminder/timer engine is *simpler* here than on modern Android. This is the single biggest reason this old hardware is a good target.
- **No Health Services** (Wear OS 3 only). Raw `SensorManager` if HR is ever needed.
- **No Ongoing Activity API** (API 30+). A running Pomodoro cannot surface on the watch face the modern way — it must be a foreground service + notification.
- **Notification channels are mandatory** (API 26+).
- **Background execution limits** (API 26+) — no plain background services.
- **Tiles**: shipped for Wear OS 2, but newer ProtoLayout releases may have drifted toward Wear OS 3. Pin a version and *verify the Tile actually renders on 2.45* before building on it.
- **Compose for Wear OS** supports minSdk 25 and will run, but on an SD3100 with 1 GB RAM it may jank badly. **This is the open question the probe exists to settle.**

## Agreed build order

1. **Toolchain + hardware probe** ← WE ARE HERE
2. Room schema (Task / Reminder / TimerPreset) + repository
3. Compose-for-Wear (or Views) shell, benchmarked on-device immediately
4. Alarm + notification layer (reminders and timers share it)
5. Voice quick-capture + stem-button bindings
6. Tile + complication

---

# YOUR TASK RIGHT NOW

Build, install, and run **CZProbe**, then capture its logcat.

The probe exists so we design against facts instead of guesses. It has **zero dependencies on purpose** — plain `Activity`, `Theme.DeviceDefault`, no AndroidX, no Compose. If it won't deploy, the problem is the toolchain, not the code. Do not "improve" it by adding libraries.

## Step 1 — does the project exist?

If a `CZProbe` project is already in this directory, use it. **If not, create it from the appendix at the bottom of this file.**

Most reliable path on Windows: generate an empty Wear OS project in Android Studio (so you inherit a working Gradle wrapper and current AGP/Kotlin versions), then overwrite with the appendix files and set `minSdk = 26`, `targetSdk = 28`.

## Step 2 — find the watch

```
adb devices
```

**This is genuinely unresolved and matters.** The watch is physically connected to the PC by its magnetic charging puck. It is unclear whether that puck carries USB data.

- **If the watch appears as `device`** → the puck carries data. Use USB. Done.
- **If nothing appears** → the puck is power-only, and we go over Wi-Fi:
  - On the watch: Settings → System → About → tap **Build number** ×7
  - Settings → **Developer options** → enable **ADB debugging**, **Debug over Wi-Fi**, and **Stay awake while charging**
  - Settings → Connectivity → Wi-Fi → join the **same network as the PC**. Wear OS 2 often defaults Wi-Fi to "Automatic" and only connects when Bluetooth is out of range — force it on.
  - `adb connect <watch-ip>:5555`, then **accept the authorization prompt on the watch**. It's tiny and it times out — tap fast, tick "always allow".

**Report which of these two turned out to be true.** It sets our deployment loop for the whole project.

## Step 3 — build, install, launch

```
gradlew installDebug
adb shell am start -n com.czprobe/.MainActivity
adb logcat -c
adb logcat -s CZPROBE:V
```

## Step 4 — have the user exercise the hardware

While logcat streams, ask them to:

1. Press the **top pusher**
2. Press the **bottom pusher**
3. **Click** the crown
4. **Rotate** the crown both directions

## Step 5 — report back exactly these

| Question | Log section |
|---|---|
| Confirm `SDK_INT` == 28 | `BUILD` |
| `memoryClass`, `largeMemoryClass`, `totalMem`, `lowRamDevice` | `MEMORY` |
| Keycode each pusher emits (expect `STEM_1` / `STEM_2`) | `KEY DOWN` |
| Does the **crown click** reach the app as `STEM_PRIMARY`, or does the system swallow it? | `KEY DOWN` |
| Does a `SOURCE_ROTARY_ENCODER` input device exist? | `INPUT DEVICES` |
| `scaledVerticalScrollFactor` and raw rotary axis values | `ROTARY` |
| Real sensor list with vendors | `SENSORS` |
| Exact px / dp / density / `isScreenRound` | `DISPLAY` |

**The two that matter most:**

- **`memoryClass` + `lowRamDevice`** → decides **Compose vs. Views** for the task list. Do not skip or hand-wave this.
- **Whether the crown click is interceptable** → decides whether we can bind it to an app action, or whether only the two pushers are ours.

Paste the raw logcat back, don't just summarize it.

## Gotchas — do not rediscover these the hard way

- **The watch drops its ADB connection when the screen sleeps.** "Stay awake while charging" must be on, and keep it on the charger.
- **Rotary events go to the FOCUSED view.** The probe calls `requestFocus()` on the ScrollView for exactly this reason. Omit that in the real app and the crown looks dead — people waste hours chasing a hardware bug that isn't there.
- **`adb connect` fails silently** across subnets (guest/IoT SSIDs are the usual culprit) or through Windows Firewall.
- The watch is a **round** screen with no chin. Debug text needs generous padding or the corners eat it.

---

# Appendix — complete CZProbe source

Create these exactly. Zero dependencies is the point.

## `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "CZProbe"
include(":app")
```

## `build.gradle.kts (root)`

```kotlin
// Version numbers here are a starting point. If you generate the project shell in
// Android Studio, keep ITS versions and only take the source files from this tree.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
```

## `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

## `app/build.gradle.kts`

**Change `targetSdk` to 28.** The snippet below still reads 33.

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.czprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.czprobe"
        // 26, not 25: SOURCE_ROTARY_ENCODER and the Tiles API both need 26,
        // and the watch is API 28 anyway. Costs us nothing, kills lint noise.
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Deliberately zero dependencies.
dependencies { }
```

## `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.type.watch" />

    <application
        android:allowBackup="false"
        android:label="CZ Probe"
        android:theme="@android:style/Theme.DeviceDefault">

        <!-- No phone companion required. -->
        <meta-data
            android:name="com.google.android.wearable.standalone"
            android:value="true" />

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

## `app/src/main/res/layout/activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/scroll"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000"
    android:padding="26dp"
    android:scrollbars="vertical">

    <TextView
        android:id="@+id/out"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:fontFamily="monospace"
        android:textColor="#E0E0E0"
        android:textSize="9sp" />
</ScrollView>
```

## `app/src/main/java/com/czprobe/MainActivity.kt`

```kotlin
package com.czprobe

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.TextView

/**
 * Citizen CZ Smart (P990MV-01) hardware probe.
 *
 * Zero dependencies on purpose — plain Activity + Theme.DeviceDefault, no AndroidX,
 * no Compose. If this won't deploy, the problem is the toolchain, not our code.
 *
 * Run it, then:
 *   1. Press the top pusher.
 *   2. Press the bottom pusher.
 *   3. Click the crown.
 *   4. Rotate the crown both directions.
 *
 * Everything mirrors to logcat:  adb logcat -s CZPROBE:V
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "CZPROBE"
    }

    private lateinit var scroll: ScrollView
    private lateinit var out: TextView
    private val buffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scroll = findViewById(R.id.scroll)
        out = findViewById(R.id.out)

        // Rotary events go to the FOCUSED view. Without this the crown appears dead.
        // This is the #1 reason people think their watch has no rotary input.
        scroll.isFocusableInTouchMode = true
        scroll.requestFocus()

        dumpBuild()
        dumpWearVersion()
        dumpFeatures()
        dumpDisplay()
        dumpMemory()
        dumpInputDevices()
        dumpSensors()

        section("LIVE INPUT")
        emit("Press both pushers, click the crown, then rotate it.")
    }

    // ---------------- buttons ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        emit(
            "KEY DOWN  code=$keyCode (${KeyEvent.keyCodeToString(keyCode)}) " +
                "repeat=${event.repeatCount} dev=${event.device?.name}"
        )
        return if (isStem(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        emit("KEY UP    code=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")
        return if (isStem(keyCode)) true else super.onKeyUp(keyCode, event)
    }

    /** Consume only stem keys — leave BACK alone or you can't exit the app. */
    private fun isStem(keyCode: Int) = keyCode == KeyEvent.KEYCODE_STEM_PRIMARY ||
        keyCode == KeyEvent.KEYCODE_STEM_1 ||
        keyCode == KeyEvent.KEYCODE_STEM_2 ||
        keyCode == KeyEvent.KEYCODE_STEM_3

    // ---------------- rotary crown ----------------

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val raw = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            val factor = ViewConfiguration.get(this).scaledVerticalScrollFactor
            val px = -raw * factor
            emit("ROTARY    raw=$raw  factor=$factor  -> ${px.toInt()}px  dev=${event.device?.name}")
            scroll.scrollBy(0, px.toInt())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---------------- dumps ----------------

    private fun dumpBuild() {
        section("BUILD")
        emit("SDK_INT        ${Build.VERSION.SDK_INT}")
        emit("RELEASE        ${Build.VERSION.RELEASE}")
        emit("SECURITY_PATCH ${Build.VERSION.SECURITY_PATCH}")
        emit("INCREMENTAL    ${Build.VERSION.INCREMENTAL}")
        emit("MANUFACTURER   ${Build.MANUFACTURER}")
        emit("BRAND          ${Build.BRAND}")
        emit("MODEL          ${Build.MODEL}")
        emit("DEVICE         ${Build.DEVICE}")
        emit("PRODUCT        ${Build.PRODUCT}")
        emit("HARDWARE       ${Build.HARDWARE}")
        emit("BOARD          ${Build.BOARD}")
        emit("FINGERPRINT    ${Build.FINGERPRINT}")
    }

    /** The watch's Wear OS version == the version of the Wear OS system app. */
    private fun dumpWearVersion() {
        section("WEAR OS")
        listOf(
            "com.google.android.wearable.app" to "Wear OS by Google",
            "com.google.android.gms" to "Play services",
            "com.google.android.apps.wearable.settings" to "Wear settings"
        ).forEach { (pkg, label) ->
            val v = try {
                packageManager.getPackageInfo(pkg, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                "NOT INSTALLED"
            }
            emit("$label = $v")
        }
    }

    private fun dumpFeatures() {
        section("FEATURES")
        listOf(
            PackageManager.FEATURE_WATCH,
            PackageManager.FEATURE_NFC,
            PackageManager.FEATURE_LOCATION_GPS,
            PackageManager.FEATURE_MICROPHONE,
            PackageManager.FEATURE_BLUETOOTH_LE,
            PackageManager.FEATURE_WIFI,
            PackageManager.FEATURE_SENSOR_HEART_RATE,
            PackageManager.FEATURE_SENSOR_BAROMETER,
            PackageManager.FEATURE_SENSOR_STEP_COUNTER,
            PackageManager.FEATURE_TELEPHONY
        ).forEach { f ->
            emit("${if (packageManager.hasSystemFeature(f)) "YES" else " no"}  $f")
        }
    }

    private fun dumpDisplay() {
        section("DISPLAY")
        val c = resources.configuration
        val dm = resources.displayMetrics
        emit("round          ${c.isScreenRound}")
        emit("pixels         ${dm.widthPixels} x ${dm.heightPixels}")
        emit("dp             ${c.screenWidthDp} x ${c.screenHeightDp}")
        emit("density        ${dm.density}  (${dm.densityDpi} dpi)")
        emit("scrollFactor   ${ViewConfiguration.get(this).scaledVerticalScrollFactor}")
    }

    /** Tells us how hard we can push Compose on 1 GB of RAM. */
    private fun dumpMemory() {
        section("MEMORY")
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        emit("memoryClass    ${am.memoryClass} MB")
        emit("largeMemClass  ${am.largeMemoryClass} MB")
        emit("totalMem       ${mi.totalMem / (1024 * 1024)} MB")
        emit("availMem       ${mi.availMem / (1024 * 1024)} MB")
        emit("lowRamDevice   ${am.isLowRamDevice}")
    }

    /** Confirms the rotary encoder exists before we design the whole UI around it. */
    private fun dumpInputDevices() {
        section("INPUT DEVICES")
        InputDevice.getDeviceIds().forEach { id ->
            val d = InputDevice.getDevice(id) ?: return@forEach
            val rotary = d.supportsSource(InputDevice.SOURCE_ROTARY_ENCODER)
            emit("[$id] ${d.name}")
            emit("     sources=0x${Integer.toHexString(d.sources)}  ROTARY=$rotary")
        }
    }

    private fun dumpSensors() {
        section("SENSORS")
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getSensorList(Sensor.TYPE_ALL).forEach { s ->
            emit("type=${s.type} ${s.name}")
            emit("     vendor=${s.vendor} max=${s.maximumRange} power=${s.power}mA")
        }
    }

    // ---------------- output ----------------

    private fun section(title: String) {
        emit("")
        emit("===== $title =====")
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        buffer.append(line).append('\n')
        out.text = buffer
    }
}
```

