# Changelog
## 0.6.4
- Player: **Left** opens the Regions / Groups menu and **Right** the channel list while playing, as on the stick (volume stays on + / - and the remote's volume buttons).
- Player: a **Back** button in the menu, so the mouse can leave it.
- Player UX review fixes: search fields no longer look focused at rest, a clearer white focus ring on rows, a darker backdrop behind the menu, larger and brighter favourite hearts, the Groups column hides when a region has a single group, and a phone layout with scrolling region and group chips.
## 0.6.3
- Player: **Regions** column added, so the menu is Regions, Groups, Channels in the same order as on the stick. Group names lose their country prefix (like "PL|").
- Favourites: the channel list is saved in Home Assistant whenever the group list loads and when you add or remove a favourite, so it opens even when the app isn't running. A clearer message appears when a list was never saved.
## 0.6.2
- Player: groups are in the same order as on the stick (no more alphabetical sort).
- Player: there is one **Favourites** group now, the stick's own, with its channels. The heart (or H) adds and removes favourites on the stick, so they are shared with the TV app. This needs the app running on a stick.
- Clearer empty-list messages for Favourites and Recent.
- Fixed the manifest key order that made the hassfest check fail, and Validate no longer runs twice on a release tag.
## 0.6.1
- **Player works while no stick runs the app.** Home Assistant now remembers the last channel catalogue (groups, channels you opened) and the provider login, so the IPTV panel no longer shows an empty list when the TV is on another app. Open the HA IPTV app on a stick once so HA can learn the catalogue.
- **Search all channels** from the channel menu (2+ letters), **Favourites** (heart on a channel, or H) and a **Recent** group. The menu opens with a group already loaded.
- Clearer message when no groups can be loaded.
## 0.6.0
- **Browser player.** New **IPTV** panel in the Home Assistant sidebar (`/iptv-player`): full-screen live TV in any desktop browser, resumes your last channel, channel menu (groups and channels), keyboard control (arrows, Enter, Space, M, F, G, Backspace) and a transparent on-screen remote that appears when the mouse is near the bottom-right corner (click the corner button to pin it). Press F or double-click for full screen.
- **Launcher card** `custom:ha-iptv-card`: muted live preview of the last channel and a Watch button that opens the player.
- Streams go through Home Assistant (login required, works over HTTPS); your provider URL and credentials never reach the browser. The player needs at least one stick online to read the provider login.
- App 0.9.5: removed the About entry from Settings (the version is shown under Software update).
## 0.5.4
- App 0.9.4: **BACK on Home now asks "Exit HA IPTV?"** (Exit / Stay, Stay is focused, a second BACK dismisses it), so a stray press no longer drops you to the Fire TV launcher. The dialog uses the app's own look.
- Update check: runs 10 s after start (was 25 s) and at most every 30 minutes (was 6 hours). If the update was found while a channel was playing, you are asked as soon as you go back to Home.
## 0.5.3
- App 0.9.3: Live TV menu fixes from the UX review. The Regions column is wider (regions 20 %, groups 27 %, channels 53 %), so "Favs" and other region names are no longer cut to "F…". Only the focused row has an outline now; the row you are on in another column is shown as a plain filled pill, so it is clear where the cursor is. Inside Favourites the per-row star is gone (every row is a favourite), which gives channel names more room. Counts and channel numbers are brighter.
## 0.5.2
- App 0.9.2: denser Live TV menu. Smaller row text (18 sp), tighter rows and smaller channel logos show about 9–10 groups and 7–8 channels at once (before: 7 and 5). The channels column is wider (regions 16 %, groups 33 %, channels 51 %), so long channel names truncate less.
- First release signed with the fixed key, so a TV can update itself from it (0.5.1 was built before the signing secret worked; sticks on 0.9.1 from a local build update to 0.9.2 normally).
## 0.5.1
- App 0.9.1: **updates itself from the TV.** Shortly after start the app looks for a newer release on GitHub (at most every 6 hours). On the Home screen it offers "Install now / Later"; while a channel is playing it only shows a short banner. Settings has a "Software update" row to check and install by hand. The first time, Android asks you to allow "Install unknown apps" for HA IPTV and to confirm the install; after that it is one OK press.
- App 0.9.1: the Live TV menu (regions, groups, channels) no longer shows the now-playing / programme bar at the bottom, so the lists have the full height.
- Releases are signed with one fixed key (GitHub secret `IPTV_KEYSTORE_B64`), which Android requires to update an app in place.
## 0.5.0
- **Encrypted pairing.** The TV's token no longer travels in the clear. Home Assistant and the TV each make a throw-away key (ECDH, P-256) and the token is sent AES-GCM encrypted. The 4-digit code now also covers both public keys, so someone sitting between the two (two different key exchanges) makes the screens show different digits. An old TV app without encryption is refused with a clear message: update the app on the TV (0.9) and the integration together.
- App 0.9: Settings has a "Home Assistant" row (not paired / waiting / connected now / last contact N min ago), Settings scrolls when it has more rows than fit, and the Home screen can no longer end up with nothing focused (a D-pad press always lands on a tile).
## 0.4.4
- App 0.8.2: film and episode progress saved before sync existed is now shared too, so every TV's Continue watching list matches (it used to show only what was watched after sync was added).

## 0.4.3
- App 0.8.1: fixed a black screen after the first channel load on a newly paired TV. A command from Home Assistant that arrived while channels were loading (for example the TV's name) hid the splash without showing the Home screen. The name command now runs at once, and the Home screen always appears when a command starts no playback.

## 0.4.2
- If a TV has no provider login and none can be copied, the setup asks for it in Home Assistant (server, username, password; sent to the TV, never stored). The same fields are in the integration's Configure dialog to set or replace the login later.
- App 0.8: the "Connected to Home Assistant" screen offers "Enter provider details on this TV instead" next to Cancel.

## 0.4.1
- The approved token is never lost: if the provider login cannot be copied the TV is still added and a notification says why. The login is now copied automatically (untick-style opt-out "Do not copy"), and only offered when the other TV runs app 0.6 or newer.
- App 0.7: first start asks how to set up (Home Assistant or typing), numbered steps with hints if nothing happens, an "approved, finish in Home Assistant" screen, a large pairing code with Deny as the default button, "Allow pairing again" in Settings.

## 0.4.0
- Add a new TV without typing: the stick announces itself (mDNS), Home Assistant offers it under Discovered, a 4-digit code is shown on both screens and you approve on the TV remote; the access token is handed over automatically.
- Optionally copy the provider login from a TV that is already set up.
- App 0.6: pairing prompt, "Allow pairing again" in Settings; paired sticks reject new pairing requests unless opened there.

## 0.3.0
App 0.5. **All your TVs stay in step**, and you can carry on watching in another room.

**Sync between TVs (through Home Assistant)**
- Favourites, the order of regions and groups, "Continue watching" positions for films and episodes, watched marks and the last live channel are shared between every stick that is set up in Home Assistant. Change something on one TV and the others follow within seconds; a TV that was off catches up when it comes back.
- Edits on two TVs at the same time are merged (two different favourites both survive; for the same item the newest edit wins). Removing a favourite on one TV removes it everywhere.
- Home shows **Continue** with what another TV played most recently (channel, film or episode, and where you stopped). OK starts it from the same place.
- One stream per account: when another TV starts playing, this one stops and says so (Settings > Hand over playback turns it off).
- Settings > "This stick's name" (or the new "Name of this TV" option in Home Assistant) is what the other TVs show, for example "Living room".
- Provider address, username and password are never shared; add them on each stick.

**Home Assistant**
- New button **Continue here** on every TV, and service `ha_iptv.continue_on` (target TV, optional source TV): stops the source, then starts the same channel or film/episode on the target from the exact position, waking the TV and opening the app first when needed. `ha_iptv.sync_now` forces a sync.
- New diagnostic sensor **Last synced**.
- The media player now shows films and episodes (title, poster, position, duration), not only channels.
- Each TV is its own device named after the TV, so two sticks no longer look the same.
- HACS ready: brand icon, hassfest and HACS validation workflow.

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
