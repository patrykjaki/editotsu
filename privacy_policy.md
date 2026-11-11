# Privacy Policy

Last Updated: Aug 25, 2026

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

### Third-Party Authentication and Integration
Editotsu allows you to authenticate with the following third-party services:
- AniList
- MyAnimeList
- Discord

When using Discord Rich Presence (RPC) functionality, the app will share your current watching activity with Discord, including:
- The series you are watching
- The current episode number

This information is only shared while you are actively using Editotsu and have Discord RPC enabled.

While we facilitate these connections, we do not store your login credentials. Authentication tokens are stored securely on your device only. Please note that these third-party services may collect additional information according to their own privacy policies, which we encourage you to review:
- [AniList Privacy Policy Link](https://anilist.co/terms)
- [MyAnimeList Privacy Policy Link](https://myanimelist.net/about/privacy_policy)
- [Discord Privacy Policy Link](https://discord.com/privacy)

### Comments (off by default)
Comments are off until you turn them on (Comments tab on a show's page, or Settings > Tracking). The comments server, `api.dantotsu.app`, is run by the Dantotsu project, not by Editotsu. Editotsu does not control it or the data stored there.

While comments are off, Editotsu sends nothing to that server.

When you turn comments on, Editotsu sends your AniList login token to `api.dantotsu.app` to sign you in, and the server receives your AniList ID, username and avatar URL. After that it receives the comments you post and your votes. How the Dantotsu project stores or uses this data is set by that project, not by this policy.

Turning comments off, or logging out of AniList, deletes the comments sign-in saved on your device. It does not delete data already held by the comments server.

## How We Use Your Information

### Crash Reports and Analytics
We use crash reports and analytics to:
- Identify and fix technical issues
- Improve app stability
- Enhance user experience
- Contact users about specific crash issues when necessary
- Understand app usage patterns across different regions

You can control your privacy preferences:
- Anonymize crash reports through app settings
- Control app permissions through your device settings

## Data Storage and Security
- Authentication tokens are stored locally on your device
- Comment data, if you turn comments on, is stored on the Dantotsu project's comments server (see Comments above)
- We implement appropriate security measures to protect your information

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
If you have any questions about this Privacy Policy, please contact us via:
- GitHub: [Editotsu Issues](https://github.com/patrykjaki/editotsu/issues)
- GitHub: [Editotsu Issues](https://github.com/patrykjaki/editotsu/issues)
- AniList: "rebelonion"
