# __PROJECT_NAME__

## Requirements

- JDK 17
- Android SDK 36

Set `sdk.dir` in `local.properties` or set the `ANDROID_HOME` environment variable to the Android SDK directory.

## Build

On macOS or Linux:

```sh
./gradlew assembleDebug
```

On Windows:

```bat
gradlew.bat assembleDebug
```

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Application ID: `__APPLICATION_ID__`.
