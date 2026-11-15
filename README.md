# HA IPTV

A Fire TV / Android TV **media player app** for Xtream Codes–compatible playlists you already have, plus a Home Assistant integration to control it.

[![Open your Home Assistant instance and open this repository in HACS.](https://my.home-assistant.io/badges/hacs_repository.svg)](https://my.home-assistant.io/redirect/hacs_repository/?owner=eneasz&repository=ha-iptv&category=integration)
[![Open your Home Assistant instance and start setting up HA IPTV.](https://my.home-assistant.io/badges/config_flow_start.svg)](https://my.home-assistant.io/redirect/config_flow_start/?domain=ha_iptv)

1. **Download** adds the repository to HACS (one click, then restart Home Assistant).
2. **Add integration** starts the setup. A stick running the app shows up under *Discovered* by itself.

> ## ⚠️ Important – please read
> **This project does not provide, sell, host, recommend, index or link to any IPTV service, playlist, channel list, credentials or content of any kind.** It is an empty player, like VLC or Kodi: it contains no channels and no provider. You must supply your own lawfully obtained subscription/playlist, and you are solely responsible for making sure that what you watch is licensed in your country.
> The author has no affiliation with any IPTV provider or reseller and does not support or encourage piracy. Issues or pull requests that ask for, share or promote playlists, providers or credentials will be closed and deleted.
> Provided "as is" under the MIT licence, without warranty. Not affiliated with Home Assistant, Amazon or any streaming-app vendor.

```
android/            the app (Kotlin, Media3). Package pl.home.channeldeck, label "HA IPTV"
custom_components/  ha_iptv – HA integration (local push)
tests/              pytest-homeassistant-custom-component suite
```

## App
- **Movies and Series** (Xtream VOD): categories, poster grid, "Recently added", "Continue watching", search ("Find"), film cards and a show card with season tabs and episodes. Remembers where you stopped, plays the next episode by itself, shows the real stream quality (4K / FHD / HD / SD, codec, audio) and has an audio and subtitle picker (MENU).
- Regions (text before `|` in group names: UK, IE, PL…) → groups → channels; reorderable at both levels (hold OK / MENU), remembers last position.
- Plays MP2 audio even on devices without an MP2 decoder.
- Anti-freeze: a stalled or frozen stream is restarted automatically (up to 3 times per channel).
- Flags channels that play but show a black or frozen picture (sensor + event in HA).
- Logos, EPG (now / next / ends in), real stream resolution vs the claim in the channel name (flags fake "4K").
- Favourites: hold OK on a channel.
- Search: the "Find" entry at the top of the regions list opens an on-screen keyboard and lists matching channels across all groups (zap stays inside the results). FF/REW page through any list.
- Live check: press ▶/⏸ on a group (or search results) to tune each channel briefly and record whether it shows a picture. Verified channels collect under **● Live**; known off-air ones get an OFF tag. Channels you watch are recorded too. In HA: `ha_iptv.survey_group`.
- Local API on port 8765 (Bearer token): `/api/state[?wait=v]` (long-poll), `/api/regions|groups|channels|search|epg|quality`, `POST /api/command`.
- Set the token once: `adb shell am start -n pl.home.channeldeck/.MainActivity --es token SECRET`.
- ADB fallback / cold start: `--es cmd play --es query "News One"`, `--es cmd region --es region PL --ez play true`, `cmd zap --ei delta 1`, `cmd stop|pause|resume|info|home|survey`.

## Home Assistant
**Install with HACS:** HACS > ⋮ > Custom repositories > add `https://github.com/eneasz/ha-iptv`, category *Integration* > install **HA IPTV** > restart Home Assistant. (Manual: copy `custom_components/ha_iptv` to `config/custom_components/`.)
Install the app (`ha-iptv-app-*.apk` from the latest release) on each Fire TV stick and start it. Home Assistant lists the stick under *Discovered*; click Configure, check that the same 4-digit code is on both screens and press OK on the TV remote. If another TV is already set up, its provider login is copied for you (no typing). A paired stick refuses new pairings until you pick *Settings › Allow pairing again* on it.

**Set up:** Settings > Devices & services > Add integration > *HA IPTV* once **per stick** (host, port 8765, token). Give each a name such as "Living room" and, optionally, the androidtv media_player and the TV so HA can wake everything when the app is closed.

## Several TVs in step
Add every stick to Home Assistant and they share favourites, region/group order, "Continue watching" positions (films and episodes), watched marks and the last live channel. Home Assistant is the hub: it collects each stick's changes, merges them (last write wins per item) and passes them on; a stick that was off catches up when it returns. Provider credentials are never shared.

- **Continue on another TV:** the stick's Home screen offers *Continue* with whatever another TV played last, from the same position. In Home Assistant press **Continue here** on a TV, or call `ha_iptv.continue_on` (`entry_id` = target, optional `source_entry_id`); it stops the source first (one stream per account), then starts the same channel or film/episode on the target, waking the TV and the app if needed.
- **Hand over playback:** when another TV starts playing, the one that was playing stops (Settings > Hand over playback).
- The merge rules and the sync API (`/api/sync`) are described in `custom_components/ha_iptv/sync.py` and `android/.../Sync.kt`.

Entities: `media_player` (browse region→group→channel, play_media, source=region, progress bar from EPG; shows films and episodes with position), sensors (channel, group, region, now, next, ends at, resolution, measured quality, survey progress), binary sensors (app reachable, quality mismatch, stream problem), selects (region, group), buttons (zap, info, favourite, reload, **continue here**), `calendar` TV guide for favourites.

Services: `play_channel`, `zap`, `set_region`, `set_group`, `favourite`, `continue_on`, `sync_now`, `watch_programme` / `remove_watch` / `list_watches` (tune in when a show starts), `sleep_timer`, `survey_group` / `stop_survey`, `quality_report`, `search_channels`, `get_guide`.

Events: `ha_iptv_channel_changed`, `ha_iptv_programme_started`, `ha_iptv_quality_mismatch`, `ha_iptv_stream_error`, `ha_iptv_watch_triggered`.

Example – bedtime news then sleep:
```yaml
action:
  - service: ha_iptv.play_channel
    data: {query: "News One", group: PL}
  - service: ha_iptv.sleep_timer
    data: {minutes: 45, turn_off_tv: true}
```

## Third-party
- MP2 (MPEG-1 Layer II) audio is decoded in software with [JLayer](https://github.com/umjammer/jlayer) (LGPL-2.0), used as an unmodified Maven dependency, because many Android TV devices have no MP2 decoder. Other libraries: AndroidX Media3 (Apache-2.0), Coil (Apache-2.0).

Tests: `python -m pytest` (needs pytest-homeassistant-custom-component).
