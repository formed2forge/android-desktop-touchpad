# TODO – Current status and next steps

## ✅ Done

### Cursor movement via /dev/uhid
- UHID virtual HID mouse works on Pixel 8 Pro, Android 16
- Oneway AIDL for non-blocking IPC
- Multi-strategy: UHID → sendevent → shell input (fallback)
- Diagnostic panel with 6 tests

### Basic gestures
- 1-finger drag = cursor movement
- 1-finger tap = left click
- 2-finger vertical drag = scroll

### UI
- Buttons: Connect, Diagnostics, Copy, Share
- Event counters for debugging
- External display detection via DisplayManager

---

## 🔴 Phase 1 – Current sprint

### 1. Lower cursor sensitivity
- `sensitivity`: 2.5f → 1.5f (default, configurable via settings)

### 2. Raise scroll sensitivity
- `scrollSensitivity`: 0.03f → 0.08f (default, configurable)

### 3. Right click (2-finger tap)
- TouchpadView: detect a two-finger tap (< 200ms, no movement)
- AIDL: `oneway void rightClick(displayId, x, y)`
- InputService: `sendMouseReport(2, 0, 0)` (BTN_RIGHT = bit 1)

### 4. Tap-and-drag (1 finger holds + 2nd moves)
- 1st finger stays put > 200ms, 2nd finger added = drag mode
- AIDL: `oneway void startDrag(displayId)`, `oneway void endDrag(displayId)`
- InputService: `sendMouseReport(1, 0, 0)` holds the button, cursor movement continues

### 5. Pinch zoom (2 fingers apart/together)
- Detect change in distance between fingers
- Implementation: Ctrl + scroll via shell/UHID

### 6. 3-finger navigation gestures
- Left = Back (`input keyevent 4`)
- Right = Task manager (`input keyevent 187`)
- Up = App drawer (`input keyevent 284`)
- Down = Notifications (`cmd statusbar expand-notifications`)
- AIDL: `oneway void sendKeyEvent(displayId, keyCode)`, `oneway void sendShellCommand(displayId, command)`

### 7. Fullscreen UI
- Immersive mode (hide status bar + nav bar)
- Touchpad fills the whole screen
- Small gear icon (top-right, semi-transparent) → opens settings

### 8. Settings panel (BottomSheet)
- Cursor sensitivity slider (0.5–4.0)
- Scroll sensitivity slider (0.01–0.20)
- Diagnostics button
- Disconnect button
- Persisted to SharedPreferences

---

## 🟡 Phase 2 – Improvements

### 9. Configurable gesture shortcuts
- UI for mapping gesture → action
- Support: keyevent code, shell command

### 10. Haptic feedback
- Vibration on click, right click

### 11. Auto-launch
- BroadcastReceiver for monitor connection

### 12. Manual keyboard toggle — ✅ implemented, pending hardware verification
- Keyboard-icon button next to Settings summons the phone's own software keyboard via a hidden
  `KeyboardCaptureView`, relaying every keystroke (including autocorrect/predictive text) to
  the external display instead of typing locally. See `APPROACHES.md` for the `FLAG_NOT_FOCUSABLE`
  interaction this needed to work around.

---

## 🟢 Phase 3 – Release

### 12. Release build + signing
- Keystore, signing config, ProGuard
- GitHub Actions workflow for release APK

### 13. Google Play Store
- Developer account ($25 one-time)
- Store listing, screenshots, description
- Explain the Shizuku dependency in the listing

### 14. Monetization (optional)
- Freemium model: basic features free, premium via in-app purchase
- Or a tip jar via Google Play Billing
- Alternatives: Ko-fi, GitHub Sponsors (outside the Play Store)

---

## Known issues

- Cursor movement is very slow/choppy when the pointer ends up on the phone's own (internal)
  display — impractical to use there. Low priority to fix directly: since the touchpad UI is
  fullscreen on the internal display, there's no legitimate reason for the cursor to ever be
  there. Root cause of it landing there at all was tracked down to the "Universal cursor" OS
  setting (see `APPROACHES.md`) — with it off (now documented as required in `README.md`),
  the cursor doesn't reach the internal display in the first place, so this shouldn't come up
  in normal use.

## Compatibility

| Device | Support |
|----------|---------|
| Pixel 8/9 Pro | ✅ Tested (Android 16) |
| Pixel 8/9 | Should work (same Desktop Mode) |
| Samsung (DeX) | Probably works (UHID is kernel-level) |
| Motorola (Ready For) | Probably works |
| Other USB-C DP devices | Depends on Desktop Mode support |

**Requirements:** Android 14+ with Desktop Mode, Shizuku, USB-C video output
