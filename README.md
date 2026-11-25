# devlink-android

A native Android LAN agent that exposes the devlink v0.1.0 API over HTTP. The foreground service listens on port `8787`, restricts requests to private and local source addresses, and requires a bearer token. It advertises itself through Android NSD and provides device information, extension discovery, status, and the `device.battery`, `android.adb`, `power.keep-awake`, and `network.wifi` extensions.

The main screen displays the service state, the Wi-Fi address and port, and the bearer token. It also provides an on-device switch for each extension. Extension changes take effect without restarting the service. `android.adb` and `power.keep-awake` are disabled by default and must be explicitly enabled on the device. The service starts when the app opens and can be stopped or started from the screen.

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

## Secure settings permission

To allow wireless debugging and keep-awake settings to be changed, grant `WRITE_SECURE_SETTINGS` once after installing the app:

```sh
adb shell pm grant com.adachsoft.devlink android.permission.WRITE_SECURE_SETTINGS
```

Then open devlink on the device and explicitly switch on `android.adb` and/or `power.keep-awake`. The API cannot change extension enablement. Enabling the ADB extension does not grant the permission; without it, write requests return `409 Conflict` with code `permission-missing`. `power.keep-awake` and `android.adb` are disabled by default. Android 13 and later also prompt for notification permission at runtime.

The `android.adb` extension supports enable on boot and after Wi-Fi reconnects. Enable `android.adb` on the device, grant the permission, then use `PUT /ext/android.adb/v1/enable-on-boot` to opt in. After boot or a Wi-Fi reconnect, the service waits for an IPv4 address and makes at most one enable attempt for that connection when the extension is enabled, the preference is on, and `WRITE_SECURE_SETTINGS` is granted. Android may still reject wireless debugging on an untrusted network.

## Read the bearer token

The token is generated on first start, stored in the app's `SharedPreferences`, and written to the app-specific external files directory:

```sh
adb shell cat /sdcard/Android/data/com.adachsoft.devlink/files/token.txt
```

Treat this token as a secret. Replace `192.168.1.50` below with the Wi-Fi address shown on the device screen:

```sh
TOKEN="$(adb shell cat /sdcard/Android/data/com.adachsoft.devlink/files/token.txt | tr -d '\r\n')"
BASE_URL="http://192.168.1.50:8787"
curl -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/v1/info"
curl -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/v1/status"
```

## Extension examples

Get battery state:

```sh
curl -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/ext/device.battery/v1/state"
```

Read ADB state, enable or disable wireless debugging, and configure enable-on-boot:

```sh
curl -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/ext/android.adb/v1/state"
curl -X POST -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/ext/android.adb/v1/wireless/enable"
curl -X POST -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/ext/android.adb/v1/wireless/disable"
curl -X PUT -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -d '{"enabled":true}' "${BASE_URL}/ext/android.adb/v1/enable-on-boot"
```

Read and update the keep-awake setting. The `sources` field is optional when enabling; omitting it selects AC, USB, and wireless charging:

```sh
curl -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/ext/power.keep-awake/v1/state"
curl -X PUT -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -d '{"enabled":true,"sources":["ac","usb","wireless"]}' \
  "${BASE_URL}/ext/power.keep-awake/v1/state"
```

Read Wi-Fi connection details or request a best-effort reconnection. Android may redact the SSID and BSSID; devlink does not request location permission or toggle Wi-Fi:

```sh
curl -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/ext/network.wifi/v1/state"
curl -X POST -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/ext/network.wifi/v1/reconnect"
```
