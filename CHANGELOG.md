# Changelog
## 0.2.1
App 0.4.1. The Home Assistant integration is unchanged.

- Series: hold OK on an episode to start it from the beginning. Episodes with a saved position now say "hold OK to start over". Films already had a Start over button.

## 0.2.0
App 0.4 adds **Movies** and **Series** (Xtream VOD), a calmer player and a fix for the stick running out of memory. The Home Assistant integration is unchanged.

**Movies and Series**
- The Movies and Series tiles on Home are live. Categories on the left, a poster grid on the right, "Recently added", "Continue watching" and "Find" (search by title across every category).
- Show card for series: season tabs and an episode list. Focus lands on the episode to continue ("Resume from 12:03" / "Up next"), watched episodes get a tick, and the next episode starts by itself when one ends.
- Film card: blurred poster backdrop, runtime, rating, genre, plot, cast and director, Resume / Start over. Duplicate copies of the same title (4K / HD / different providers) are merged, preferring 4K.
- Resume positions are remembered per film and per episode.

**Player (films and episodes)**
- OK shows the timeline first; OK again pauses or resumes. Left/Right seek 10 s, Up/Down 1 min (hold to go faster), with a "+10 s" note and time left.
- Stream quality line: 4K / FHD / HD / SD, codec, resolution and audio (for example `4K · H.265 · 3840x2160 · E-AC-3 5.1`).
- MENU opens an audio and subtitle picker.
- Buffering now shows the animated orb from the splash instead of three dots.

**Fixes**
- Fire TV sticks with 1.7 GB of RAM could kill the app while a 4K stream started (the animated orb filled a ~440 MB GPU cache). The orb now draws through the CPU, the player buffer is capped at 48 MB and poster images are released when playback starts.

## 0.1.0 / app 0.3
- New look: neon yellow + blue. Animated voice-assistant style orb on the loading splash, then a Home screen (Live TV, Movies and Series marked SOON, Settings) with a Resume card for the last channel. Settings holds stream format, refresh and API info. BACK now goes picture -> list -> Home.
- A stream stuck in "buffering" for ~12 s is retried automatically on the other stream format (ts/m3u8).
- Favourites: gold ★ badge on channel rows, a clear "Added to / Removed from Favourites" banner above the list, and hold-OK detected from key repeats (more reliable).
- BACK steps back one view (picture -> list at the playing group -> picture); it no longer exits the app (use HOME).
- Lists update in place, so a changed ● Live list can no longer steal focus and switch your group.
- Up/Down stay inside the current list (regions, groups or channels), stop at the first/last row and never wrap, even when scrolling fast.
- "● Live" region and OFF tag: group/search live check records which channels really show a picture; HA browse shows [OFF AIR].
- Anti-freeze watchdog: restarts a stream whose playback position stalls or whose picture freezes (up to 3 times), then reports it.
- Software MP2 audio decoder (channels that were silent on devices without MP2 support).
- Black/frozen picture detection; network retry with backoff; channel search ("Find"); page jump with FF/REW.
- Renamed Channel Deck → HA IPTV; regions level; local HTTP API with long-poll; favourites; quality survey.
- HA integration: media_player with browse, sensors, calendar, services, events, watches, sleep timer.
