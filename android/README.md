# Fabula Android

Kotlin + Jetpack Compose client for the Fabula audiobook server.

## Stack

- Kotlin 2.0 + Jetpack Compose (Material 3)
- Media3 / ExoPlayer with a `MediaSessionService` for background playback,
  notification controls and Bluetooth / lockscreen integration
- Retrofit + `kotlinx-serialization` for the REST API
- OkHttp data source for ExoPlayer (HTTP range streaming)
- Coil 3 for cover images
- DataStore Preferences for the server URL
- Min SDK 26 (Android 8.0), target SDK 35

## Building

Open `android/` in Android Studio (Koala or newer). The Gradle wrapper is
committed, so the first sync will download the Android Gradle Plugin
8.7.3 and the required SDK components automatically.

Alternatively from the command line (JDK 17 + Android SDK required):

```bash
cd android
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # installs on a connected device
```

## Running

On first launch the app shows a settings screen asking for the Fabula
server URL. Enter the address of your server, for example
`http://192.168.1.20:5075`. The URL is persisted with DataStore
preferences; change it later from the ⚙ icon on the library screen.

Note: the manifest sets `android:usesCleartextTraffic="true"` so that an
unsecured home server on the LAN is reachable. For a production setup
put Fabula behind TLS (e.g. a reverse proxy) and remove this flag.

## Current features

- Library grid with cover art and progress indicators
- Book detail with chapter list and metadata
- Media3-backed playback: background, notification, lockscreen,
  Bluetooth controls, auto-advance across multi-file books
- Progress syncs back to the server every few seconds

## Not yet (planned)

- Bookmarks management
- Series browser
- Offline downloads
- Authentication (currently uses the server's single-user mode)
