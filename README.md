# Editotsu

<div align="center">

[![Latest release](https://img.shields.io/github/v/release/patrykjaki/editotsu?include_prereleases&label=latest%20release&style=for-the-badge&labelColor=14161E&color=0EC2B0)](https://github.com/patrykjaki/editotsu/releases)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-blue?style=for-the-badge&labelColor=14161E&color=1B1C2A)
[![License](https://img.shields.io/badge/license-UPL%20%2F%20GPLv3-blue?style=for-the-badge&labelColor=14161E&color=1B1C2A)](LICENSE.md)
[![Stars](https://img.shields.io/github/stars/patrykjaki/editotsu?style=for-the-badge&labelColor=14161E&color=1B1C2A)](https://github.com/patrykjaki/editotsu)

</div>

Editotsu is an Android anime and manga client based on
[Dantotsu](https://github.com/rebelonion/Dantotsu).

It includes a native libmpv player, AniList and MyAnimeList integration, a manga
reader, offline downloads, subtitle controls, Discord Rich Presence and an
updated source picker.

## Features

### Playback

Editotsu uses libmpv for video playback, with hardware decoding, external
audio tracks, video adjustments, debanding filters, and audio/subtitle sync
controls.

### Tracking

Anime and manga can be tracked with AniList and MyAnimeList, with progress
synchronized between devices. MyAnimeList sign-in tokens are stored encrypted
on-device.

### Source picker

The source picker uses compact cards and tags to make different sources easier
to compare. Resolution, codec, HDR, audio and subtitle information can be shown
without having to read through the entire source name.

When available, details such as release group, tracker, seeders and file size
are shown separately. Direct streams use a simpler compact layout.

The picker is still being refined and its design will continue to improve in
future releases.

### Subtitles

ASS subtitles can keep their original styling or use Editotsu's subtitle
appearance. Automatic mode chooses between them based on the type of media being
played, and custom subtitle controls are also available. Font family, size,
colors and outline can be configured.

### Manga and offline use

Editotsu includes a built-in manga reader. Anime and manga can also be
downloaded for offline use, and downloaded content and caches can be managed
from the app.

### Discord Rich Presence

Optional Discord Rich Presence can show what you're currently watching or
reading. Editotsu does not require or store a Discord account token for Rich
Presence.

## Downloads

Get builds from [GitHub Releases](https://github.com/patrykjaki/editotsu/releases).

- **Google build** — normal build for most users.
- **Degoogle'd build** — build without Google/Firebase integration. The
  Degoogle'd APK uses the historical `fdroid` build-flavor name. It is built
  by Editotsu and is not an official F-Droid repository release.
- **arm64** builds suit most modern Android phones; **universal** builds
  cover other architectures.
- Verify downloads against the release's `SHA256SUMS`.

Beta builds install as `ani.editotsu.beta` and coexist with the stable
`ani.editotsu` app.

## Experimental features

Torrent playback is currently experimental and may not work reliably with every
device or media source.

## Privacy

Firebase crash reporting and analytics are currently disabled in Editotsu. See
the [privacy policy](privacy_policy.md) for details.

## Building

Prerequisites:

- Android Studio
- Android SDK / compile API 37
- JDK 21

```bash
git clone https://github.com/patrykjaki/editotsu.git
cd editotsu

./gradlew assembleGoogleDebug

./gradlew :app:testGoogleDebugUnitTest :app:testFdroidDebugUnitTest
```

## About media and third-party services

Editotsu is an open-source client application and does not host or distribute
media files.

Some functionality can work with independently developed third-party extensions
or external services. Those projects and services are not operated or controlled
by Editotsu.

Use Editotsu only with media and services you are legally permitted to access
and according to the terms of those services.

## Credits

- [Dantotsu](https://github.com/rebelonion/Dantotsu) — the original client this project is based on.
- [mpv](https://github.com/mpv-player/mpv), [mpv-android](https://github.com/mpv-android/mpv-android) and [mpv-android-lib](https://github.com/abdallahmehiz/mpv-android-lib) — playback engine and Android bindings.
- [Aniyomi](https://github.com/aniyomiorg/aniyomi) and [Tachiyomi](https://github.com/tachiyomiorg/tachiyomi) — extension and reader foundations.

## License

Editotsu is open-source software licensed under the Unabandon Public License (UPL), which incorporates the terms of the GNU General Public License v3 (GPLv3). See [LICENSE.md](LICENSE.md).
