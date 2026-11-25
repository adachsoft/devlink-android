# devlink-android

A native Android LAN agent that exposes the devlink v0.1.0 API over HTTP. The foreground service listens on port `8787`, restricts requests to private and local source addresses, and requires a bearer token. It advertises itself through Android NSD and currently provides device information, extension discovery, status, and the `device.battery` extension.

The main screen displays the service state, the Wi-Fi address and port, and the bearer token. The service starts when the app opens and can be stopped or started from the screen.

## Requirements

- JDK 17
- Android SDK 36

Set `sdk.dir` in `local.properties` or set the `ANDROID_HOME` environment variable to the Android SDK directory.

## Build and install

On macOS or Linux:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows:

```bat
gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Application ID: `com.adachsoft.devlink`.

## Permissions

No special ADB permission grant is required for this stage. Android 13 and later prompt for notification permission at runtime. `WRITE_SECURE_SETTINGS` is not used in stage 1; stage 2 may add capabilities that require it.

## Read the bearer token

The token is generated on first start, stored in the app's `SharedPreferences`, and written to the app-specific external files directory:

```sh
adb shell cat /sdcard/Android/data/com.adachsoft.devlink/files/token.txt
```

Treat this token as a secret. Replace `192.168.1.50` below with the Wi-Fi address shown on the device screen:

```sh
TOKEN="$(adb shell cat /sdcard/Android/data/com.adachsoft.devlink/files/token.txt | tr -d '\r\n')"
curl -H "Authorization: Bearer ${TOKEN}" http://192.168.1.50:8787/v1/info
curl -H "Authorization: Bearer ${TOKEN}" http://192.168.1.50:8787/v1/status
```
