# Spine+

Spine+ is an offline music and video player for Android with a downloader built in.
Type a song's name or paste a link, and it lands on your shelf.

It is made by [Growix Studio](https://growixstudio.xyz/spine-plus).

## Get the app

- **Download:** the newest version is always at
  [Releases](https://github.com/SafinRweb/spine-plus-releases/releases/latest).
  The file is an APK for 64-bit ARM phones, Android 11 and newer.
- **Updates:** an installed Spine+ checks this page and offers the new version itself.
- **Help and privacy:** [support](https://growixstudio.xyz/spine-plus/support) and the
  [privacy policy](https://growixstudio.xyz/legal/spine-plus/privacy).

## The source

This repository holds the complete source of the app as it is released.

- From version 1.2.1 on, each release's own tag (`v1.2.1`, ...) is the source of that
  release, and GitHub attaches it to the release as "Source code".
- Version 1.2.0 was released before the source was put here. Its source is the tag
  [`source-1.2.0`](https://github.com/SafinRweb/spine-plus-releases/tree/source-1.2.0).

## Build it

You need JDK 17 or newer (the app is built with JDK 21) and the Android SDK with
platform 36.

1. Tell Gradle where the Android SDK is: set `ANDROID_HOME`, or write a `local.properties`
   file in this folder with one line, `sdk.dir=/path/to/android-sdk`.
2. Build:

   ```bash
   ./gradlew :app:assembleDebug
   ```

3. The APK is in `app/build/outputs/apk/debug/`.

A build of your own is signed with your own key, so Android will not install it over a
copy downloaded from Releases: uninstall that one first, or change the `applicationId` in
`app/build.gradle.kts`. To sign a release build, see the notes at the top of that file.

## What it is built from

- Kotlin and Jetpack Compose.
- [Media3](https://github.com/androidx/media) (Apache 2.0) for playback.
- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (GPL 3.0), which
  packs [yt-dlp](https://github.com/yt-dlp/yt-dlp), Python, FFmpeg and QuickJS for
  Android. This is the download engine.
- The Barlow typeface (SIL Open Font License, see `licenses/Barlow-OFL.txt`).

## Licence

Spine+ is free software under the GNU General Public License, version 3. The full text
is in [LICENSE](LICENSE). It comes with no warranty.

Download only what is yours to keep: your own work, or work you have the right to copy.

Copyright (C) 2026 Growix Studio.
