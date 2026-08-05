# Tested approaches – log

Device: Pixel 8 Pro, Android 16 (SDK 36), Shizuku UID 2000

---

## Approaches to cursor movement

### 1. InputManager.getInstance() + injectInputEvent (reflection)
- **Status: DOESN'T WORK**
- `InputManager.getInstance()` was removed in Android 16 SDK 36
- The method doesn't exist → `NoSuchMethodException`

### 2. InputManagerGlobal.getInstance() + injectInputEvent
- **Status: DOESN'T WORK (returns true, but the cursor doesn't move)**
- `Class.forName("android.hardware.input.InputManagerGlobal")` works
- Both the 2-arg and 3-arg `injectInputEvent()` return `true` on displayId 0 and 197
- **Problem**: MotionEvent injection delivers events to windows but does NOT move the system cursor. PointerController only reads from kernel input devices.

### 3. Instrumentation.sendPointerSync()
- **Status: DOESN'T WORK**
- `SecurityException`: "not directed at window owned by uid 2000"

### 4. Shell `input` commands
- **`input -d <displayId> tap X Y`**: WORKS for tap/click
- **`input motionevent HOVER_MOVE X Y`**: DOESN'T WORK — `IllegalArgumentException`
- **`input mouse move X Y`**: DOESN'T WORK — "Unknown command: mouse"
- **`input keyevent <code>`**: WORKS — for navigation actions (Back, Home, Recent, All Apps)
- **`cmd statusbar expand-notifications`**: UNTESTED — for the notification shade

### 5. /dev/uhid – Virtual HID Mouse ✅ WORKS
- **Status: WORKS (current strategy)**
- UHID_CREATE2 creates a virtual HID mouse via `/dev/uhid`
- HID report descriptor: 3-button relative mouse + wheel (52 bytes)
- The cursor appears and moves on the external display
- **Bugs fixed:**
  - Synchronous AIDL + flush() → app freeze → fix: `oneway` AIDL, removed flush()
  - @Synchronized on AIDL methods → binder thread starvation → fix: @Synchronized only on sendMouseReport()
  - Infinite loop in uhidMove() → `toInt()` rounded 0.5–0.99 down to 0 → fix: `round()` + break guard
  - Report counter: 3,298,750 reports for 2 calls to moveCursor = proof of the infinite loop

### 6. /dev/uinput – Virtual Input Device
- **Status: UNTESTED (available)**
- `/dev/uinput` exists and is writable by shell (UID 2000)
- Requires ioctl() → needs JNI or a C binary
- Deferred — UHID works

### 7. sendevent shell command
- **Status: IMPLEMENTED as a fallback**
- `sendevent /dev/input/eventN EV_REL REL_X value` etc.
- Slower than UHID (shell overhead), but functional as a backup

### 8. AccessibilityService
- **Status: NOT TRIED**
- GestureDescription API — probably doesn't support a secondary display
- Doesn't need Shizuku, but limited capabilities

---

## Approaches to AIDL communication

### Synchronous AIDL
- **DOESN'T WORK** — blocks the UI thread, the app freezes on fast movement

### Oneway AIDL ✅
- **WORKS** — fire-and-forget, the UI thread doesn't block
- `diagnose()` stays synchronous (returns a String)

### @Synchronized on AIDL methods
- **DOESN'T WORK** — binder thread starvation, diagnose() never gets a turn
- Fix: @Synchronized only on sendMouseReport() (protects the fd write)

---

## Key findings

1. **DisplayId isn't sequential** — IDs tend to be 196, 197, 203, etc.
2. **MotionEvent injection doesn't move the cursor** — a fundamental Android limitation
3. **UHID works** — the kernel creates a pointer, reports move the cursor
4. **`/proc/bus/input/devices` requires root** on Android 16
5. **`/sys/class/input/*/device/name` isn't readable by shell** — must use `getevent -pl` instead
6. **An infinite loop is sneaky** — it shows up as "the cursor doesn't move" even though reports are being sent (millions of reports with dx=0, dy=0)
