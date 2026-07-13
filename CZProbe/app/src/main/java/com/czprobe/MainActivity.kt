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
import android.view.View
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
        // The dumps overflow the screen; land on the LIVE INPUT prompt so key
        // presses are visible without scrolling. Never auto-scroll from the
        // rotary handler — the crown is the user's own scroll control.
        scrollToEnd()
    }

    // ---------------- buttons ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        emit(
            "KEY DOWN  code=$keyCode (${KeyEvent.keyCodeToString(keyCode)}) " +
                "repeat=${event.repeatCount} dev=${event.device?.name}"
        )
        scrollToEnd()
        return if (isStem(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        emit("KEY UP    code=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")
        scrollToEnd()
        return if (isStem(keyCode)) true else super.onKeyUp(keyCode, event)
    }

    private fun scrollToEnd() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
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
