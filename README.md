<div align="center">
  <h1>🎬 Editotsu</h1>
  <p><strong>A high-performance MPV-based fork of Dantotsu for Android</strong></p>

  <p align="center">
    <img src="https://img.shields.io/badge/platforms-Android%208.0%2B-06599d?labelColor=7000b7&color=1b1c2a&style=for-the-badge"/>
    <img src="https://img.shields.io/badge/engine-libmpv%20v0.41-red?style=for-the-badge&labelColor=1b1c2a&color=e53935"/>
    <img src="https://img.shields.io/badge/license-UPL%20%2F%20GPLv3-blue?style=for-the-badge&labelColor=1b1c2a&color=1e88e5"/>
  </p>
</div>

---

## 📖 About Editotsu

**Editotsu** is an optimized, feature-rich fork of [Dantotsu](https://github.com/rebelonion/Dantotsu) that replaces the standard video pipeline with a native **`libmpv`** playback architecture. 

Designed for anime enthusiasts who demand hardware-accelerated playback of high-bitrate media, pixel-perfect subtitle rendering, and seamless tracking integration with **AniList** and **MyAnimeList**.

---

## ✨ Key Features

* 🚀 **Native `libmpv` Playback Engine**:
  * Hardware-accelerated decoding (`hwdec=auto`) with full support for 10-bit H.265/HEVC, AV1, VP9, and legacy codecs.
  * Direct rendering via OpenGL ES / GPU shaders with zero-copy texture streaming.

* 💬 **Pixel-Perfect Subtitle Rendering**:
  * Full native support for advanced **ASS/SSA** subtitles, including complex typography, positioning, embedded fonts, and karaoke styling.
  * Custom override options for font family, size, colors, borders, and background opacity.

* ⚡ **Optimized Stream Caching**:
  * Low-latency demuxer readahead buffers for fast startup on direct streams (HLS / MP4).
  * Smooth seeking with back-buffer caching for instant short-range rewinds without re-downloading.

* 🖼️ **Refined Picture-in-Picture (PiP)**:
  * Safe aspect-ratio clamping (`1:2.39` to `2.39:1`) to prevent layout crashes.
  * Smooth Android 12+ swipe-to-home gesture transitions with dynamic source rectangle hints.
  * Clean UI overlay handling in PiP mode.

* 📊 **Smart Tracking & Integrations**:
  * Real-time progress synchronization with **AniList** and **MyAnimeList**.
  * **AniSkip** integration for automatic opening and ending skip.
  * Discord Rich Presence integration.

---

## 🛠️ Building from Source

### Prerequisites
* Android Studio Ladybug (or newer) / IntelliJ IDEA
* Android SDK (API 34–36)
* Android NDK (r27c / r28c)
* Java 17+ (JDK 17)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/patrykjaki/editotsu.git
cd editotsu

# Build Universal Debug APK
./gradlew assembleGoogleDebug

# Run Unit Test Suites
./gradlew testGoogleDebugUnitTest
```

---

## 🤝 Acknowledgments & Credits

Editotsu is built on the incredible work of the open-source community:

* **[Dantotsu](https://github.com/rebelonion/Dantotsu)** by [rebelonion](https://github.com/rebelonion) — The original feature-packed anime tracking client.
* **[mpv](https://github.com/mpv-player/mpv)** — The ultra-versatile, free, and open-source media player.
* **[mpv-android](https://github.com/mpv-android/mpv-android)** & [mpv-android-lib](https://github.com/abdallahmehiz/mpv-android-lib) — Android native bindings for libmpv.
* **[Aniyomi](https://github.com/aniyomiorg/aniyomi)** & **[Tachiyomi](https://github.com/tachiyomiorg/tachiyomi)** — The foundation for extension and manga reader components.

---

## 📜 License

Editotsu is open-source software licensed under the **Unabandon Public License (UPL)**, which incorporates all terms of the **GNU General Public License v3 (GPLv3)**.

See the [`LICENSE.md`](LICENSE.md) file for the complete license text.

<br>

---

# 📚 Original Dantotsu Documentation

<div align="center">
  <img src="https://files.catbox.moe/s3mwfr.png" alt="Dantotsu Banner" width="830">

  <p align="center">
    <img src="https://img.shields.io/badge/platforms-Android%208.0%2B-06599d?labelColor=7000b7&color=1b1c2a&style=for-the-badge"/>
    <a href="https://git.rebelonion.dev/rebelonion/Dantotsu/releases"><img src="https://img.shields.io/badge/dynamic/json?url=https://git.rebelonion.dev/api/v1/repos/rebelonion/Dantotsu/releases/latest&query=assets[0].download_count&label=Downloads&style=for-the-badge&color=1b1c2a&labelColor=7000b7" alt="Downloads"></a>
  </p>

  <p align="center">
    <a href="https://git.rebelonion.dev/rebelonion/Dantotsu/src/branch/main/"><img src="https://img.shields.io/badge/code%20quality-A-1b1c2a?labelColor=7000b7&style=for-the-badge" alt="CodeFactor"/></a>
    <a href="https://git.rebelonion.dev/rebelonion/Dantotsu/stars"><img src="https://img.shields.io/gitea/stars/rebelonion/Dantotsu?gitea_url=https%3A%2F%2Fgit.rebelonion.dev&style=for-the-badge&labelColor=7000b7&color=1b1c2a" alt="Stars" /></a>
  </p>
</div>

## Dantotsu: Anilist-Only Tracking Client

**Dantotsu (断トツ; Dan-totsu)** literally means "the best of the best" in Japanese. Try it out for yourself and be the judge!

> [!IMPORTANT]  
> **Dantotsu is a tracking tool only.** It does not host, provide, distribute, or endorse any streaming content, media, or third-party extensions.
>
> **User Responsibility:** By downloading, installing, or using this application, you agree to use it in compliance with all applicable laws, not infringe on copyrighted content, and take full responsibility for any extensions you install or use. Users must comply with all applicable laws, copyright, and intellectual property rights.
>
> **No Liability:** The developer(s) are not responsible for third-party extensions, user actions, misuse, legal issues, or violations. The developers do not provide, maintain, or endorse any extensions that enable access to unauthorized content. Legal concerns should be directed to the respective third-party services, not Dantotsu. The app is provided "as-is" without warranties.
>
> **Services:** Dantotsu integrates only with the official API of Anilist. The extension system is designed to integrate with legal streaming services like Jellyfin. Third-party extensions are the responsibility of their creators, not the Dantotsu developer(s).
>
> **By using Dantotsu, you agree to comply with our [Terms of Service](./privacy_policy.md). Please review the ToS to understand our DMCA-compliant, tracking functionality and our non-involvement with any content or services beyond AniList, MyAnimeList, and Discord.**

## Downloads
<div align="center">
  <p>
     <a href="https://git.rebelonion.dev/rebelonion/Dantotsu/releases/latest"><img src="https://img.shields.io/badge/dynamic/json?url=https://git.rebelonion.dev/api/v1/repos/rebelonion/Dantotsu/releases/latest&query=tag_name&label=Stable&style=for-the-badge&color=1b1c2a&labelColor=7000b7" alt="Latest Stable Release"/></a>
     <a href="https://git.rebelonion.dev/rebelonion/Dantotsu/releases"><img src="https://img.shields.io/badge/dynamic/json?url=https://git.rebelonion.dev/api/v1/repos/rebelonion/Dantotsu/releases?draft=false&pre-release=true&query=$[0].tag_name&label=Beta&style=for-the-badge&color=1b1c2a&labelColor=7000b7" alt="Latest Pre-release"/></a>
   </p>
</div>

## Support Us
<a href="https://github.com/sponsors/rebelonion"><img src="https://img.shields.io/badge/GitHub%20Sponsors-rebelonion-1b1c2a?style=for-the-badge&logo=githubsponsors&logoColor=white&labelColor=7000b7" alt="GitHub Sponsors"/></a>
> [!TIP]
> ⭐ **Star this repository to support the developer & encourage the development of the app!**

## Official Communities
Join our communities to stay updated and contribute to the discussion:

<a href="https://discord.gg/4HPZ5nAWwM" style="margin-right: 10px; display: inline-block;"><img src="https://files.catbox.moe/4mo8cz.png" alt="Discord" height="40" style="vertical-align: middle;"></a>
<a href="https://www.reddit.com/r/dantotsu" style="display: inline-block;"><img src="https://files.catbox.moe/6me8pn.png" alt="Reddit" height="40" style="vertical-align: middle;"></a>

## Contribute
All contributions are welcome, from code to documentation to graphics to design suggestions to bug reports. Please use GitHub to its fullest; contribute Pull Requests, tutorials, or other content—whatever you have to offer, we can use!  
For inquiries, join our [Discord server](https://discord.gg/4HPZ5nAWwM). Pull requests are welcome; check the [open issues](https://git.rebelonion.dev/rebelonion/Dantotsu/issues) for guidance on major changes.

## Visitors
<img src="https://count.getloli.com/get/@:rebeloniondantotsu" alt=":rebeloniondantotsu" />

## Acknowledgments
A heartfelt thank you to everyone who has contributed to the development of Dantotsu.  
Your efforts are invaluable.

<a href="https://git.rebelonion.dev/rebelonion/Dantotsu/activity/contributors">
  <img alt="GitHub contributors" src="https://img.shields.io/github/contributors/itsmechinmoy/dantotsu?style=flat-square&label=Contributors%20%3A&labelColor=%230f1318&color=%230f1318" align="left">
</a>
<br>
<a href="https://git.rebelonion.dev/rebelonion/Dantotsu/activity/contributors">
  <img src="https://contrib.rocks/image?repo=itsmechinmoy/dantotsu" alt="Contributors">
</a>