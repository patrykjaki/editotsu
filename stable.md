# 0.2.0-alpha

- **Performance & Torrent Fast-Path:**
  - Fast-path 1-piece torrent streaming startup (instant playback transition on piece 0)
  - Active 24-piece lookahead runway with staggered deadline scheduling
  - Defensive disk-read retry loop with lazy file recovery
  - Hardened loopback HTTP server with single-range streaming and socket lifecycle tracking
- **Upstream Merge:**
  - Upstream LNReader novel extension fixes & improvements

