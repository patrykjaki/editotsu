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

### Comments System
Our in-house comments API uses your AniList authentication token for verification purposes when you initially open Editotsu. While this token is used for authentication, it is never stored on our servers. We only store:
- Your AniList ID (for authentication)
- Profile picture URL
- Comments you post
- Your upvotes on comments

We do not track or store:
- Your AniList authentication token
- Content you view or watch
- Browsing history
- Watch history
- Personal information beyond what's listed above

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

### Comments System
We use comment data to:
- Display your comments to other users
- Show upvote counts
- Associate comments with your profile

## Data Storage and Security
- Authentication tokens are stored locally on your device
- Comment data is stored securely on our servers
- We implement appropriate security measures to protect your information

## Data Sharing
We do not sell or share your personal information with third parties except:
- When required by law
- To provide the core functionality of the app through our integrated services

## Your Rights and Data Protection
Under the General Data Protection Regulation (GDPR) and other data protection laws, you have certain rights regarding your personal data. You can:
- Access your comment data
- Request deletion of your comments and associated data

To exercise these rights or request deletion of your data, you can contact the developer:
- On AniList: "rebelonion"
- GitHub: [Editotsu Issues](https://github.com/patrykjaki/editotsu/issues)

While we don't have an automated process for data deletion, we will process your request manually as soon as possible.

## Children's Privacy
Our service is not directed to children under 13. We do not knowingly collect personal information from children under 13.

## Changes to This Privacy Policy
We may update this Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page and updating the "Last Updated" date.

## Contact Us
If you have any questions about this Privacy Policy, please contact us via:
- GitHub: [Editotsu Issues](https://github.com/patrykjaki/editotsu/issues)
- GitHub: [Editotsu Issues](https://github.com/patrykjaki/editotsu/issues)
- AniList: "rebelonion"
