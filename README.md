<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/assets/editotsu-wordmark-dark.svg">
    <img src=".github/assets/editotsu-wordmark-light.svg" alt="Editotsu" width="640">
  </picture>

  <p align="center">
    <img src="https://img.shields.io/badge/platforms-Android%208.0%2B-00AE9F?labelColor=162129&color=1b2a33&style=for-the-badge" alt="Android 8.0+"/>
    <a href="LICENSE.md"><img src="https://img.shields.io/badge/license-UPL%20%2F%20GPLv3-1b2a33?labelColor=162129&style=for-the-badge" alt="License"/></a>
    <a href="https://github.com/patrykjaki/editotsu/stargazers"><img src="https://img.shields.io/github/stars/patrykjaki/editotsu?style=for-the-badge&labelColor=162129&color=1b2a33" alt="Stars"/></a>
  </p>
</div>

# Editotsu: AniList and MAL tracking client

**Editotsu** is a fork of [Dantotsu](https://github.com/rebelonion/Dantotsu), "the best of the best",
with a few edits of its own. Hence the name.

> [!IMPORTANT]
> **Editotsu is a client and tracking app.** It does not host, provide, distribute
> or index any media, and it ships with no content sources.
>
> **Extensions are third-party.** Playback and reading only work through extensions
> that you choose to install. They are developed and maintained by their own
> authors, not by Editotsu. The extension system is meant for services and media
> you are permitted to access, such as your own media server.
>
> **Your responsibility.** By installing or using Editotsu you agree to use it in
> line with the law where you live and with the terms of the services you connect
> to, and you take responsibility for any extensions you install. Editotsu is
> provided as-is, without warranty.
>
> **Services.** Editotsu talks to the official APIs of AniList, MyAnimeList,
> AnimeThemes and Discord. Questions about content belong to the service or
> extension that provides it. See the [privacy policy](privacy_policy.md).

## Downloads

<div align="center">
  <p>
    <a href="https://github.com/patrykjaki/editotsu/releases/latest"><img src="https://img.shields.io/github/v/release/patrykjaki/editotsu?label=stable&style=for-the-badge&labelColor=162129&color=00AE9F" alt="Latest stable release"/></a>
    <a href="https://github.com/patrykjaki/editotsu/releases"><img src="https://img.shields.io/github/v/release/patrykjaki/editotsu?include_prereleases&label=pre-release&style=for-the-badge&labelColor=162129&color=1b2a33" alt="Latest pre-release"/></a>
  </p>
</div>

- **Google build**: the normal build for most users.
- **Degoogle'd build**: no Google or Firebase code. It uses the historical
  `fdroid` flavor name, is built by Editotsu, and is not an official F-Droid
  repository release.
- **arm64** suits most current phones; **universal** covers the other
  architectures.
- Check downloads against the release's `SHA256SUMS`.

Pre-releases install as `ani.editotsu.beta` (Editotsu β / α), next to the stable
`ani.editotsu`.

## What Editotsu adds

- **mpv player** with Vulkan rendering, hardware decoding and GPU upscaling (CuNNy shaders)
- **AnimeThemes**: openings and endings on every anime page, with favourites, a spoiler shield and offline audio
- **AniList and MyAnimeList** at the same time
- **Full backup and restore** of settings and app data in one file

> [!TIP]
> ⭐ **Star this repository** if you like the app. It helps more people find it.

## Contribute

Bug reports, ideas and pull requests are welcome. Please check the
[open issues](https://github.com/patrykjaki/editotsu/issues) first, and open one
before a large change.

## Privacy

Firebase crash reporting and analytics are turned off. Details are in the
[privacy policy](privacy_policy.md).

## Building

You need Android Studio, the Android SDK with compile API 37, and JDK 21.

```bash
git clone https://github.com/patrykjaki/editotsu.git
cd editotsu

./gradlew assembleGoogleDebug

./gradlew :app:testGoogleDebugUnitTest :app:testFdroidDebugUnitTest
```

## Credits

- [Dantotsu](https://github.com/rebelonion/Dantotsu) by rebelonion and its
  contributors: the client Editotsu is built on, including the original icon
  style that Editotsu's E follows.
- [mpv](https://mpv.io) and libmpv.
- [AnimeThemes](https://animethemes.moe) for the themes database.

## License

Editotsu is licensed under the same terms as Dantotsu: the Unabandon Public License (UPL), see [LICENSE.md](LICENSE.md).
