# Pixel Touchpad

A minimal Android app that turns a phone's display into a virtual touchpad for controlling the cursor on an external monitor (desktop mode).

## Features

- **Cursor movement** – one finger, relative movement like a laptop touchpad
- **Click** – short one-finger tap
- **Scroll** – two-finger vertical drag

## Requirements

- Android 9+ (Pixel 8 with Android 15/16 ideal)
- [Shizuku](https://github.com/RikkaApps/Shizuku/releases) installed and running
- External display connected via USB-C

## How it works

1. The app connects to Shizuku and starts a privileged service
2. The service uses `InputManager.injectInputEvent()` to inject mouse events on the secondary display
3. TouchpadView on the phone captures touch gestures and translates them into cursor movement

## Local build

To build directly on your own x86_64 Linux machine (no waiting on CI), see [BUILD.md](BUILD.md).

## Build via GitHub Actions

### 1. Create a repository on GitHub

Go to [github.com/new](https://github.com/new) and create a new repository (e.g. `PixelTouchpad`).

### 2. Push the code

```bash
cd PixelTouchpad
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/PixelTouchpad.git
git push -u origin main
```

### 3. Wait for the build

- Go to the **Actions** tab in the repository
- The build starts automatically on push
- Once done (about 3-5 minutes), click the run → **Artifacts** → download `PixelTouchpad-debug.zip`

### 4. Install on the phone

- Unzip the ZIP – it contains an `.apk` file
- Transfer it to the phone and install it (allow installs from unknown sources)

## Usage

1. Start **Shizuku** and activate the service (Wireless debugging)
2. Connect the monitor via a USB-C cable
3. Turn on **desktop mode** in Developer options
4. Open **Pixel Touchpad**
5. Tap **Connect** – the app connects to Shizuku and finds the external display
6. The touchpad activates – move your finger across the phone's display

## Customization

### Touchpad sensitivity
In `TouchpadView.kt`:
```kotlin
var sensitivity = 2.5f        // cursor speed
var scrollSensitivity = 0.03f // scroll speed
```

### Click detection
In `TouchpadView.kt`:
```kotlin
private val tapMaxDuration = 200L   // max touch duration for a click (ms)
private val tapMaxDistance = 30f     // max finger movement for a click (px)
```

## Known limitations

- The cursor resets to the middle of the display after an app restart
- No right mouse button (could be added as a long-press or two-finger tap)
- Requires restarting Shizuku after a phone restart

## License

MIT – do whatever you want with it.
