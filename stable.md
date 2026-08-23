# 0.2.1-alpha

- **Torrent Speedup Engine & Smart Subtitles:**
  - Background pre-buffering on episode tap (overlaps metadata and swarm connection with UI launch)
  - Instant 0ms seek scheduling with immediate deadline window focusing
  - Smart dialogue subtitle auto-prioritization (prioritizes full dialogue tracks over signs/songs/forced tracks based on user language preference)
  - Defensive font extraction and mobile swarm tuning

# 0.2.0-alpha

- **Performance & Torrent Fast-Path:**
  - Fast-path 1-piece torrent streaming startup (instant playback transition on piece 0)
  - Active 24-piece lookahead runway with staggered deadline scheduling
  - Defensive disk-read retry loop with lazy file recovery
  - Hardened loopback HTTP server with single-range streaming and socket lifecycle tracking
- **Upstream Merge:**
  - Upstream LNReader novel extension fixes & improvements

