# Editotsu 0.5.0-beta01

> **Beta pre-release.** This build is intended for testing and may still contain compatibility issues.
>
> Version: `0.5.0-beta01` (`1000510`)

This release is a major foundation update for Editotsu, covering playback, subtitles, account integrations, persistence, updater behavior, and release infrastructure.

## Highlights

- Improved media playback and network-stream handling.
- Reworked subtitle styling with automatic behavior based on playback source.
- MyAnimeList OAuth support.
- Manga library/progress persistence improvements.
- Discord Rich Presence improvements.
- Safer and deterministic in-app update handling.
- New reproducible release/signing verification pipeline.

## Player improvements

Playback behavior has received a large compatibility and reliability pass.

Changes include:

- improved handling of network playback sources and request headers;
- fixes for external audio-track selection;
- safer player initialization and option handling;
- regression protection for stream-loading behavior;
- additional diagnostics for playback failures.

Playback has been field-tested across multiple extension-provided sources, but compatibility can still vary depending on the source, media format, network conditions, and upstream provider behavior.

## Subtitles

Subtitle handling has been substantially improved.

### Subtitle styling modes

**Automatic by source — Recommended**

Uses Editotsu's configured subtitle appearance for online streams while preserving original ASS styling for torrents, downloads, and local media.

Also available:

- **Always use Editotsu styling**
- **Always use source styling**
- **Custom**

Existing preferences are migrated conservatively.

Additional improvements include:

- bundled subtitle fonts resolve using their correct internal family names;
- Custom-only controls remain hidden unless Custom mode is selected;
- improved subtitle behavior across different playback origins.

## MyAnimeList

MyAnimeList OAuth login is now available.

The implementation includes:

- OAuth-based authentication;
- encrypted on-device token storage;
- safe handling of builds without a configured MAL client ID;
- support in both official Editotsu build flavors.

The MAL client ID used for official releases is injected during the release build and is not stored in the repository.

## Manga persistence

This release includes the reviewed manga persistence foundation, with additional lifecycle, reconciliation, and state-safety coverage.

The implementation includes dedicated regression coverage intended to prevent stale state and false-idle conditions.

## Discord Rich Presence

Discord presence support has been updated to use the reviewed tokenless RPC implementation.

No Discord account token is stored for Rich Presence.

## Updater

The in-app updater has received deterministic release and APK-selection handling.

It:

- avoids relying on GitHub asset ordering;
- selects the correct Google build flavor and device ABI;
- falls back to a compatible universal APK where appropriate;
- keeps beta and stable update channels separate;
- correctly compares Editotsu beta versions and later stable versions.

## Experimental torrent support

Torrent playback remains **experimental** in `0.5.0-beta01`.

The existing implementation is included for testing but is not considered production-complete and may have compatibility or reliability limitations.

Further torrent-engine work is planned for a later release.

## Stability and verification

Both official build flavors pass the current full unit-test suite:

- Google: **1004 tests**
- Degoogle'd: **1004 tests**

The release pipeline also verifies:

- Android package identity;
- release version;
- signing certificate;
- expected build flavor;
- generated APK assets;
- checksums;
- release provenance.

## Known issues

- Some third-party media streams use formats or segment layouts that the current Editotsu/libmpv playback path does not handle correctly. Alternate sources may work while compatibility work continues.
- Playback behavior can differ between emulators and physical Android devices.
- Torrent support remains experimental.
- The Degoogle'd build does not currently use the in-app updater.

Issues involving a third-party extension or content provider may depend on behavior outside Editotsu itself.

## Builds

### Google build

`Editotsu-0.5.0-beta01-google-arm64-v8a.apk`

Recommended for most modern Android phones.

`Editotsu-0.5.0-beta01-google-universal.apk`

Universal build for other supported Android architectures and unusual device configurations.

### Degoogle'd build

`Editotsu-0.5.0-beta01-fdroid-arm64-v8a.apk`

Degoogle'd Editotsu build for most modern Android phones.

`Editotsu-0.5.0-beta01-fdroid-universal.apk`

Universal Degoogle'd build.

The Gradle flavor retains the historical technical name `fdroid`, but these APKs are built and distributed by the Editotsu project and are not official F-Droid repository builds.

### Checksums

`SHA256SUMS`

can be used to verify downloaded release artifacts.

## Installation and package identity

Editotsu beta and alpha builds use:

`ani.editotsu.beta`

Stable Editotsu releases use:

`ani.editotsu`

Because `0.5.0-beta01` is a beta prerelease, this release installs as:

`ani.editotsu.beta`

The beta package can coexist with the stable Editotsu application.

Android treats the stable and beta package IDs as separate applications, so their application data and settings are not automatically shared.

## Third-party services and content

Editotsu is an independent open-source application.

Editotsu does not operate third-party media providers. Third-party extensions, services, and media sources are separate from the Editotsu project and may have their own terms, availability, licensing, and regional restrictions.

Users are responsible for using Editotsu and third-party services in accordance with applicable law and the terms of the services they choose to use.

Names and trademarks of third-party services remain the property of their respective owners.
