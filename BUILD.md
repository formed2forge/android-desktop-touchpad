# Local build (Arch Linux, x86_64)

Instructions for building and testing the app directly on your dev machine (no GitHub Actions needed).

## 1. Dependencies

```bash
sudo pacman -S --needed jdk17-openjdk unzip android-tools
```

- `jdk17-openjdk` – JDK 17 (exactly what `compileOptions`/`kotlinOptions` in `app/build.gradle` require)
- `android-tools` – `adb`/`fastboot` for installing and testing on the phone
- You do **not** need to install Gradle separately – the repo ships a wrapper (`./gradlew`) that downloads the exact version (8.10) itself

If you have multiple JDK versions installed, switch to 17 for this shell session:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
```

## 2. Android SDK (cmdline-tools)

The simplest approach is to download the official cmdline-tools manually (AUR packages often lag behind on SDK versions):

```bash
mkdir -p ~/android-sdk/cmdline-tools
curl -sL -o /tmp/cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q /tmp/cmdline-tools.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest

export ANDROID_HOME=~/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Add the `export ANDROID_HOME=...` and `export PATH=...` lines to `~/.bashrc` / `~/.zshrc` so you don't have to set them every time.

## 3. `local.properties`

Gradle needs to know where the SDK is. In the project root, create `local.properties` (the file is in `.gitignore`, so it's never committed):

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

## 4. Build

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. On x86_64 nothing needs emulation — `aapt2` and the other native tools in `build-tools` run directly, so the build should just work.

## 5. Install and test on the phone

```bash
adb devices          # confirm the phone shows up (USB debugging must be enabled)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then follow `README.md` (Shizuku, desktop mode, connecting the monitor).

## Notes

- `./gradlew` and `gradle/wrapper/*` are now part of the repo — the GitHub Actions "Generate Gradle Wrapper" step is redundant because of this, but it's left in place so CI still works even if the wrapper is ever accidentally deleted.
- `compileSdk 35` / `build-tools;35.0.0` require a recent enough `cmdline-tools` version (see the URL above) — older `sdkmanager` builds can't find `platforms;android-35`.
