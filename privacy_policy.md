# Privacy Policy

Last Updated: Nov 12, 2026

## Introduction
This Privacy Policy describes how we collect, use, and handle your information when you use Editotsu (a fork of Dantotsu). We are committed to protecting your privacy and ensuring transparency about our data practices.

## Information We Collect

### Crash Reports and Analytics
**Editotsu currently ships with Firebase telemetry DISABLED in all flavors.** No crash reports,
analytics events, or usage data are transmitted to any remote service by this app. Crash handling
is local-only: exceptions are written to an on-device log file that never leaves your device unless
you explicitly share it yourself.

If a self-hosted or Editotsu-owned crash reporting backend is introduced in the future, this policy
will be updated first, collection will be off by default, and any identity attachment will be
strictly opt-in.

### Accounts
You can log in to AniList and MyAnimeList. Their login tokens are stored on your device only; Editotsu has no server of its own and stores no credentials anywhere else. These services handle your data under their own policies:
- [AniList terms](https://anilist.co/terms)
- [MyAnimeList privacy policy](https://myanimelist.net/about/privacy_policy)

### Discord Rich Presence (off by default)
There is no Discord login. If you turn Rich Presence on, Editotsu passes the title, episode and cover image link of what you are watching to the Discord app on your device, which shows them on your profile while you watch. See the [Discord privacy policy](https://discord.com/privacy).

### Services the app contacts
Editotsu has no backend. It requests data directly from these services, which see your IP address and the request (for example, which title you opened):
- Tracking and metadata: AniList, MyAnimeList (official API and the Jikan API, including unofficial Jikan mirrors `jikanfortheweebs.midnightignite.me` and `api.tenrai.org`), Kitsu, TVmaze, TMDB (images and episode data), MangaUpdates, MangaBaka, ani.zip and an ID-mapping service on Hugging Face (`Isagi2025-idmapper.hf.space`), `api.imdbapi.dev`, Chiaki (watch order), Anime News Network and `kuroiru.co` (news on a show's page), and the image proxy `wsrv.nl` for TMDB images and logos.
- Playback extras: AniSkip and IntroDB (skip times; AniSkip requests can go through `corsproxy.io`), AnimeThemes (openings and endings), YouTube (trailers).
- Subtitles, when you search for them: the subtitle search services listed in the player's subtitle settings.
- Reading: Google Translate (`translate.googleapis.com`) and MyMemory for novel and OCR translation, Free Dictionary API for word lookups and, if you turn on data saver, the image proxy chosen in reader settings.
- Other: trace.moe (image search, only for images you pick), Klipy (GIF picker, comments only), GitHub and jsDelivr (app update check, extension repositories you add, add-ons), the upstream Dantotsu code host `git.rebelonion.dev` (contributors list in About), and the DNS-over-HTTPS provider if you choose one.
- Extensions and torrents: extensions you install contact the sites they are written for; torrent features contact BitTorrent trackers and peers. Editotsu does not run or control any of them.

### Comments (off by default)
Comments are off until you turn them on (Comments tab on a show's page, or Settings > Tracking). The comments server, `api.dantotsu.app`, is run by the Dantotsu project, not by Editotsu. Editotsu does not control it or the data stored there.

While comments are off, Editotsu sends nothing to that server.

When you turn comments on, Editotsu sends your AniList login token to `api.dantotsu.app` to sign you in, and the server receives your AniList ID, username and avatar URL. After that it receives the comments you post and your votes. How the Dantotsu project stores or uses this data is set by that project, not by this policy.

Turning comments off, or logging out of AniList, deletes the comments sign-in saved on your device. It does not delete data already held by the comments server.

## How We Use Your Information
Editotsu collects no analytics and sends no crash reports. A crash shows a report on your device, which you can share yourself.

## Data Storage and Security
- Login tokens, settings and history are stored on your device only
- Comment data, if you turn comments on, is stored on the Dantotsu project's comments server (see Comments above)

## Data Sharing
We do not sell or share your personal information with third parties except:
- When required by law
- To provide the core functionality of the app through our integrated services

## Your Rights and Data Protection
Under the General Data Protection Regulation (GDPR) and other data protection laws, you have certain rights regarding your personal data. You can:
- Access the data Editotsu keeps on your device (Settings > Data lets you back it up)
- Request deletion of your comments from the comments server; this has to be sent to the Dantotsu project, which runs it

For questions about Editotsu itself, contact:
- GitHub: [Editotsu Issues](https://github.com/patrykjaki/editotsu/issues)

## Children's Privacy
Our service is not directed to children under 13. We do not knowingly collect personal information from children under 13.

## Changes to This Privacy Policy
We may update this Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page and updating the "Last Updated" date.

## Contact Us
- GitHub: [Editotsu Issues](https://github.com/patrykjaki/editotsu/issues)
