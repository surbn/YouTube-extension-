# YouTube Custom — Echo Extension

Unofficial YouTube Music search/playback extension for Echo Nightly. Based on the official Echo extension template.

## Included
- Piped music search (`filter=music_songs`)
- Fallback API instances
- Highest-bitrate audio stream selection
- Custom Piped API URL setting in Echo
- GitHub Actions build workflow producing `YouTube_Custom.eapk`

## Build
Install Android Studio, Android SDK 36, JDK 17, and Gradle 9.0.0. Open this folder in Android Studio or run:

`gradle :app:assembleDebug`

Output: `app/build/outputs/apk/debug/app-debug.apk`. Rename it to `YouTube_Custom.eapk` and install it in Echo Nightly.

The build has not been executed in this environment. Compile and test before distributing. Piped instances are community-operated and may become unavailable; YouTube/region/API changes can affect playback. This is not affiliated with YouTube or Google.
