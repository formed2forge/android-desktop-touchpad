# Tested approaches – log

Device: Pixel 8 Pro, originally Android 16 (SDK 36), now updated to Android 17 — Shizuku UID 2000

---

## Touching the phone screen dismisses focused/open UI on the external display

**Symptoms**: re-selecting a point in an already-focused text field hid the keyboard; moving
toward a submenu item in the desktop window-decoration menu closed the menu immediately. Both
happened even with zero cursor movement (a stationary re-click of the exact same spot) and with
a plain tap (not just a held drag) — ruling out movement-batching and button-hold theories.
**A real USB/Bluetooth mouse never reproduced either issue**, with or without the external
display connected, which pinned it on our app specifically rather than a Desktop Mode quirk.

### Theory: touch on the internal display steals global top-focus — ✅ CONFIRMED FIXED
Every gesture on this app's touchpad requires physically touching the phone's screen. Android's
multi-display model tracks a global "top-focused" window/display (relevant to IME routing and
dismiss-on-focus-loss listeners on other windows). A real mouse never touches the phone's
screen at all, so it never disturbs this; our app touches it on every single interaction. That
fits all the data: happens on any touch regardless of movement or click duration, never happens
with a real mouse, and doesn't visibly affect plain content clicks (which mostly don't listen
for focus loss the way text fields and dismissible menus do).

**Fix**: `window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)` in `MainActivity`.
The window still receives touch normally (this flag only blocks *keyboard/input* focus, not
touch dispatch) but should no longer take system input focus when touched — we have no text
fields of our own needing IME focus, so no downside expected for our own UI. The Settings
bottom sheet is a separate `Dialog` window and keeps its own default focusability, so its
sliders/buttons are unaffected.

**Confirmed on-device**: fixed for the vast majority of apps tested. One app showed degraded
text selection in desktop mode, but that reproduced even with the PixelTouchpad app closed and
Shizuku not running — an unrelated quirk in that app's own Desktop Mode support, not something
this project caused or can fix.

---

## Quick-tap-then-hold regression — ✅ DISABLED BY DEFAULT

After shipping quick-tap-then-hold (tap, release, press-and-hold the same spot to start a
single-finger drag), two unrelated interactions broke on-device:
- Typing into a focused text field, then using the cursor to reposition within that same
  field, made the on-screen keyboard disappear until the field was deselected/reselected.
- Opening the desktop window-decoration menu, then moving the cursor toward a submenu item,
  closed the menu immediately on the first tap that started the move.

**Root cause**: the feature arms a hold-check on *every* touch-down within `tapThenHoldWindow`
(400ms) of a previous tap's release. Ordinary "click, then move the cursor elsewhere, then
click again" usage is a near-identical touch pattern — click, quickly touch down again, brief
pause before the swipe really gets moving. If that brief pause exceeds `dragHoldTime` (250ms)
within `tapMaxDistance` (30px), the code fires `onDragStart`, sending a **mouse-button-down**
via UHID at wherever the cursor happened to be *before* the intended move — which reads to the
IME/menu as a click outside the field/menu, hiding or closing it. There's no clean way to
disambiguate the two patterns from timing alone; they look identical at the start.

**Fix**: gated the feature behind `TouchpadView.enableTapThenHold`, defaulted to `false`. All
the supporting code (arming, the delayed check, cancellation) is left in place in case the
timing gets revisited later — only the single call site that arms it is guarded.

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

## Approaches to multi-display cursor routing

**Bug**: cursor defaulted to the phone's own screen even after the external display was
detected and `externalDisplayId` was known — the AIDL methods accepted a `displayId` but
`moveCursor`/UHID never actually used it to target a display.

### Root cause
A UHID virtual mouse has no display of its own. Unlike touch/MotionEvent injection (which
takes a displayId directly), a kernel input device's pointer is routed to a display by the
system's InputReader based on a device-to-display *association*, which by default doesn't
exist — so the pointer falls back to the default (built-in) display.

### `InputManager.addUniqueIdAssociationByPort` (Android 15+, SDK 35+) ✅ FIXED
- **Status: IMPLEMENTED**
- Confirmed via `scrcpy`'s PR [#6009](https://github.com/Genymobile/scrcpy/pull/6009), which
  hit the identical problem adding a UHID mouse to a virtual display and solved it the same way,
  also from a shell-UID process (their adb-pushed server) — matches our Shizuku UserService's
  execution context, giving confidence shell UID holds the needed permission.
- Requires `android.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY`, held implicitly by shell
  (UID 2000) at the binder/UID level — no manifest declaration needed, since this isn't a
  normal app-declared permission grant.
- Mechanics:
  1. When creating the UHID device, write a unique string (`uhidPhys = "pixeltouchpad:<pid>"`)
     into the UHID_CREATE2 request's `phys` field (previously left blank).
  2. Reflectively obtain a real `InputManager` bound to the live `IInputManager` binder service
     (`ServiceManager.getService("input")` → `IInputManager.Stub.asInterface(...)` → hidden
     `InputManager(IInputManager)` constructor) — avoids needing a real `Context`, which a
     Shizuku UserService process doesn't cleanly have.
  3. Reflectively obtain the target display's `uniqueId` (`DisplayManagerGlobal.getInstance()
     .getDisplayInfo(displayId).uniqueId` — a hidden field on `DisplayInfo`).
  4. Call `inputManager.addUniqueIdAssociationByPort(uhidPhys, displayUniqueId)` via reflection
     (the method exists on the device at runtime but isn't in the public SDK's compile-time stubs).
- All of the above is reflection because none of `IInputManager`, the `InputManager(IInputManager)`
  constructor, `DisplayManagerGlobal`, or `addUniqueIdAssociationByPort` are in the public SDK.
- Per the scrcpy PR discussion, order between "UHID device created" and "association registered"
  doesn't matter — Android applies it once both exist, whichever happens first.
- **Not yet verified on physical hardware** — compiles and passes CI, but this chain of hidden-API
  reflection can only be confirmed by actually running it on the Pixel 8 Pro. `diagnose()` now
  reports `uhidPhys` and `lastAssociationResult` for on-device debugging if it doesn't work.

## Approaches to keyboard (IME) display routing

**Ask**: when a text field on the external display gets focus, the on-screen keyboard pops up
*on the external display* — useless, since that monitor has no touch. Want it on the phone's
own screen instead, while keystrokes still go to the focused field on the external display.

### `IWindowManager.setDisplayImePolicy` (hidden system API) ✅ IMPLEMENTED
- Documented in AOSP's own multi-display IME support page
  (source.android.com/docs/core/display/multi_display/ime-support): the IME shows on the
  focused window's display, or falls back to the default display, based on a per-display
  policy set via `WindowManager#setDisplayImePolicy(displayId, policy)` /
  `getDisplayImePolicy(displayId)` — this is display-level system config, not something an
  app can influence just by how it builds its own window.
- Confirmed via `scrcpy`'s PR [#5703](https://github.com/Genymobile/scrcpy/pull/5703), which
  added a `--display-ime-policy` flag doing exactly this from their adb-shell server process —
  same execution context as our Shizuku UserService, so shell UID should already hold whatever
  permission this needs (same pattern as `addUniqueIdAssociationByPort` above).
- Mechanics: `ServiceManager.getService("window")` → `IWindowManager.Stub.asInterface(...)` →
  reflectively call `setDisplayImePolicy(externalDisplayId, DISPLAY_IME_POLICY_FALLBACK_DISPLAY)`.
  The policy constant is read via reflection off `android.view.WindowManager`'s own field rather
  than hardcoded, in case its integer value ever changes across API levels.
- Called once, right alongside `associateWithDisplay`, whenever `MainActivity` detects the
  external display — sets the *external* display's policy to fall back to the default
  (internal) display for IME purposes.
- **Not yet verified on physical hardware.** `diagnose()` now reports `lastImePolicyResult`.

### "Universal cursor" setting (Settings → Connected devices → External displays → built-in
display → toggle, only visible when "Mirror built-in display" is off) — ✅ ROOT CAUSE FOUND

On-device matrix testing (connect-then-launch / launch-then-connect / toggle mirror mode
while connected, × Universal cursor on/off) isolated the real cause:

| | connect→launch | launch→connect | toggle mirror while connected |
|---|---|---|---|
| Universal cursor OFF | correct | correct | correct |
| Universal cursor ON | stuck on internal display | stuck on internal (recoverable by swiping, see below) | stuck on internal |

With Universal cursor OFF, Android places and keeps the cursor on the external display
correctly in every case tested — no app code involved. With it ON, Android treats both
displays as one continuous cursor space (matching the arrangement configured in Settings),
and the cursor initially/repeatedly ends up on the internal display regardless of our
`addUniqueIdAssociationByPort` call above — that association affects which display an input
device's motion is attributed to, but not this separate OS-level "which display currently
shows the roaming cursor" behavior.

**Fix for now: document Universal cursor as required OFF** (see `README.md`) — sidesteps the
problem entirely rather than fighting undocumented OS pointer-routing behavior.

### Clamped cursor accumulator silently ate relative motion — ✅ FIXED
While Universal cursor was ON, trying to swipe the cursor from the internal display back onto
the external one worked for a while, then hit a hard stop "relative to swipe length." Cause:
`TouchpadView.cursorX/cursorY` (an internal accumulator used only to compute the delta sent to
`InputService.moveCursor`) was clamped to `[0, displayWidth/Height)` — the external display's
bounds. Once the accumulator saturated at that boundary, further finger movement kept
computing a delta of ~0 (since the clamped value stopped changing), even though the real OS
cursor — currently elsewhere, in a combined multi-display coordinate space — hadn't reached
any real boundary. UHID motion is inherently relative, so the fix was to stop clamping the
accumulator at all and only clamp a copy of it for the on-screen debug coordinate readout.

---

## Key findings

1. **DisplayId isn't sequential** — IDs tend to be 196, 197, 203, etc.
2. **MotionEvent injection doesn't move the cursor** — a fundamental Android limitation
3. **UHID works** — the kernel creates a pointer, reports move the cursor
4. **`/proc/bus/input/devices` requires root** on Android 16
5. **`/sys/class/input/*/device/name` isn't readable by shell** — must use `getevent -pl` instead
6. **An infinite loop is sneaky** — it shows up as "the cursor doesn't move" even though reports are being sent (millions of reports with dx=0, dy=0)
7. **Touching the phone screen can steal system focus from the external display** — any window that needs to receive touch but not act as the system's top-focused window should be `FLAG_NOT_FOCUSABLE`
8. **When an app misbehaves in Desktop Mode, test with this app fully closed and Shizuku stopped** — the fastest way to rule out whether it's this project's doing at all
