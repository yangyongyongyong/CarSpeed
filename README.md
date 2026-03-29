# CarSpeed

CarSpeed is an Android speed overlay app for driving scenarios. It shows current speed, peak speed, a recent speed trend chart, and trip timing in a floating HUD-style panel.

## Features

- Floating speed overlay with drag, collapse, and resize support
- Current speed, max speed, trip duration, and wait duration
- Recent speed trend chart
- GPS jitter suppression for stationary scenarios
- Driving history view with sorting
- Custom app icon and Android adaptive icon support

## Tech Stack

- Kotlin
- Android Views + Material 3
- Gradle Kotlin DSL

## Package Name

`com.thomas.carspeed`

## Local Development

```bash
cd /Users/thomas990p/iqooAPP/iqooAPP
./gradlew :app:assembleDebug
```

Run unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

## Release Build

Unsigned release bundle:

```bash
./gradlew :app:assembleRelease
```

This repository also includes local helper scripts under [`scripts/`](/Users/thomas990p/iqooAPP/iqooAPP/scripts).

## Notes

- The app requires location permission and overlay permission.
- Because the package name was migrated to `com.thomas.carspeed`, it installs as a new app instead of upgrading older builds under the old package name.
