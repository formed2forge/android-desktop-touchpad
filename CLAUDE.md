# CLAUDE.md – Instructions for Claude Code

## About the project

**PixelTouchpad** is an Android app that turns a phone's display into a virtual touchpad for controlling the cursor on an external monitor in Android Desktop Mode. Primarily targets the Pixel 8 Pro on Android 17 (SDK 37, following the API-level-per-major-version pattern from 15→35, 16→36 — worth double-checking against `Build.VERSION.SDK_INT` on-device if anything version-gated misbehaves).

## Architecture

```
TouchpadView (UI, touch events)
    ↓ callbacks (onCursorMove, onClick, onRightClick, onScroll, onDragStart/End, onThreeFingerSwipe, onPinchZoom)
MainActivity (wiring, Shizuku setup, display detection, settings)
    ↓ oneway AIDL IPC
InputService (Shizuku UserService, UID 2000 shell)
    ↓ write() / shell exec
/dev/uhid (kernel UHID → virtual HID mouse)  |  shell commands (keyevent, statusbar)
    ↓ kernel input subsystem
PointerController (system cursor on the display)
```

### 1. TouchpadView (`TouchpadView.kt`)
- Custom `View` capturing touch gestures
- Gestures (current + planned):
  - 1-finger drag = cursor movement
  - 1-finger tap = left click
  - 2-finger tap = right click
  - 2 fingers moving the same direction = scroll
  - 2-finger pinch = zoom
  - 1 finger holds + 2nd moves = drag
  - 3 fingers L/R/U/D = back/recent/app drawer/notifications
- Configurable: `sensitivity` (default 1.5), `scrollSensitivity` (default 0.08)
- Tracks absolute cursor position (cursorX, cursorY) on the external display

### 2. InputService (`InputService.kt`)
- Shizuku UserService – runs in a separate process with shell privileges (UID 2000)
- AIDL methods (oneway): associateWithDisplay, setKeyboardOnInternalDisplay, moveCursor, click, rightClick, scroll, startDrag, endDrag, sendKeyEvent, sendText, sendBackspace, sendShellCommand, destroy
- AIDL methods (synchronous): diagnose
- Strategy: UHID > sendevent > shell input (fallback)
- UHID report: buttons(1) + X(1) + Y(1) + wheel(1) + hwheel/AC Pan(1) = 5 bytes

### 3. MainActivity (`MainActivity.kt`)
- Setup flow: Shizuku permission → bind UserService → display detection → touchpad
- Fullscreen immersive mode once connected
- Settings BottomSheet: cursor/scroll sensitivity, drag-lock toggle, diagnostics, disconnect
- SharedPreferences for settings persistence

## Dependency on Shizuku

**Yes**, the whole solution depends on Shizuku:
- `/dev/uhid` requires the `uhid` group — shell (UID 2000) has it
- `sendevent`, `input keyevent` require shell privileges
- `cmd statusbar` requires shell privileges
- A normal app (UID 10xxx) has access to none of these

## Gesture map

| Gesture | Action | AIDL method | Implementation |
|-------|------|-------------|-------------|
| 1-finger drag | cursor movement (smoothed) | moveCursor | UHID REL_X/Y |
| 1-finger tap | left click | click | UHID BTN_LEFT |
| 2-finger tap | right click | rightClick | UHID BTN_RIGHT (bit 1 = 0x02) |
| 2 fingers same direction | scroll, natural direction, with momentum after lift | scroll | UHID REL_WHEEL + AC Pan (horizontal) |
| 2-finger pinch | zoom | — | Ctrl+scroll (TBD) |
| double-tap then hold; release to end by default, or a single tap to end (toggle in Settings, see `enableDragLock`/`endDragOnSingleTap`) | drag | startDrag/endDrag | UHID button=1 hold; persists across touch sessions only in single-tap-to-end mode |
| 3 fingers left | Back | sendKeyEvent(4) | `input keyevent 4` |
| 3 fingers right | Task manager | sendKeyEvent(187) | `input keyevent 187` |
| 3 fingers up | App drawer | sendKeyEvent(284) | `input keyevent 284` |
| 3 fingers down | Notifications | sendShellCommand | `cmd statusbar expand-notifications` |

## Build

- Gradle 8.10, AGP 8.5.2, Kotlin 2.0.0
- compileSdk 35, minSdk 28, targetSdk 35
- JDK 17
- Shizuku: `dev.rikka.shizuku:api:13.1.5` and `provider:13.1.5`

See [BUILD.md](BUILD.md) for local build instructions (x86_64 Linux).

## File structure

```
├── CLAUDE.md                       # This file
├── TODO.md                         # Prioritized tasks and status
├── APPROACHES.md                   # Log of every approach tried
├── BUILD.md                        # Local build instructions
├── app/src/main/
│   ├── aidl/.../IInputService.aidl # AIDL interface (oneway)
│   ├── java/.../InputService.kt    # Shizuku UserService (UHID + shell)
│   ├── java/.../MainActivity.kt    # UI + setup + settings
│   ├── java/.../TouchpadView.kt    # Touch gestures
│   ├── java/.../KeyboardCaptureView.kt  # Invisible view holding the manual IME session
│   ├── java/.../SettingsBottomSheet.kt  # Settings panel
│   └── res/
│       ├── layout/activity_main.xml          # Fullscreen layout
│       ├── layout/bottom_sheet_settings.xml  # Settings bottom sheet
│       └── values/themes.xml
```

## User context
- Martin, a Czech game developer (Godot, HTML games for kids)
- Device: Pixel 8 Pro, Android 17
- UI language: English (this fork's UI was translated from the upstream project's Czech)
- Goal: control desktop mode on an external monitor without a physical mouse
