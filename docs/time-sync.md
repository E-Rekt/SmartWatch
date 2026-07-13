# Watch time-sync — diagnosis and fix (step-2 blocker, resolved)

Investigated 2026-07-13 over Wi-Fi ADB, immediately before data-layer design.
Context: during the step-1 probe the watch's logcat timestamps were months off,
which is fatal for a reminders/timers app.

## Diagnosis (measured)

| Check | Result |
|---|---|
| `adb shell date` | `Tue Apr 22 03:09:41 CDT 2025` — **~15 months slow** (real date 2026-07-13) |
| `settings get global auto_time` | **`0` — automatic time sync disabled.** Root cause. |
| `settings get global ntp_server` | `null` (platform default, `time.android.com`) |
| Timezone | CDT — correct |

## Fix applied

```powershell
adb shell settings put global auto_time 1     # re-enable automatic time
# NTP did not correct a 15-month offset within 20 s, so set directly:
adb shell cmd alarm set-time <epoch-millis>   # shell holds SET_TIME on API 28
```

Result: watch clock matched the PC to the second. `auto_time=1` left enabled
so platform NTP can maintain it. Note `cmd alarm set-time` is the reliable
manual path on this build — no root needed.

## Consequences baked into the app design

The clock was silently wrong for over a year, so the app defends regardless:

1. **Reminders store UTC epoch millis** (`triggerAtUtc`), never local wall
   strings; local rendering derives at display time.
2. **Reschedule everything** on time/zone/boot changes — a clock jump of months
   must not strand or mass-fire alarms (step 4 implements; the schema carries
   the bookkeeping). **Measured caveat (same day):** `TIME_SET` is NOT
   delivered to manifest receivers on this firmware (not on the API-26+
   exemption list; `TIMEZONE_CHANGED` is). Step 4 must use a runtime-registered
   `TIME_SET` receiver plus a daily elapsed-axis reconcile alarm — see
   `data-layer-design.md`.
3. **Timers/stopwatch use `elapsedRealtime()`**, immune to wall-clock changes;
   persist the elapsed-realtime anchor plus a wall-clock anchor so a reboot
   (which resets elapsedRealtime) is detectable and recoverable.
4. **Drift tripwire**: the app records a `(wallClock, elapsedRealtime)` pair at
   each launch; a mismatch in the deltas between pairs reveals a clock jump
   even without network, and can prompt a "check watch time" warning.
