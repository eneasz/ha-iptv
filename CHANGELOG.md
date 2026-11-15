# Changelog
## 0.1.0 / app 0.3
- BACK steps back one view (picture -> list at the playing group -> picture); it no longer exits the app (use HOME).
- Lists update in place, so a changed ● Live list can no longer steal focus and switch your group.
- Up/Down stay inside the current list (regions, groups or channels), stop at the first/last row and never wrap, even when scrolling fast.
- "● Live" region and OFF tag: group/search live check records which channels really show a picture; HA browse shows [OFF AIR].
- Anti-freeze watchdog: restarts a stream whose playback position stalls or whose picture freezes (up to 3 times), then reports it.
- Software MP2 audio decoder (channels that were silent on devices without MP2 support).
- Black/frozen picture detection; network retry with backoff; channel search ("Find"); page jump with FF/REW.
- Renamed Channel Deck → HA IPTV; regions level; local HTTP API with long-poll; favourites; quality survey.
- HA integration: media_player with browse, sensors, calendar, services, events, watches, sleep timer.
