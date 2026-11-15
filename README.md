# HA IPTV

A Fire TV / Android TV **media player app** for Xtream Codes–compatible playlists you already have, plus a Home Assistant integration to control it.

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
Copy `custom_components/ha_iptv` to HA's `config/custom_components/`, restart, add the integration (host, port 8765, token; optionally the androidtv media_player and the TV so HA can wake everything when the app is closed).

Entities: `media_player` (browse region→group→channel, play_media, source=region, progress bar from EPG), sensors (channel, group, region, now, next, ends at, resolution, measured quality, survey progress), binary sensors (app reachable, quality mismatch, stream problem), selects (region, group), buttons (zap, info, favourite, reload), `calendar` TV guide for favourites.

Services: `play_channel`, `zap`, `set_region`, `set_group`, `favourite`, `watch_programme` / `remove_watch` / `list_watches` (tune in when a show starts), `sleep_timer`, `survey_group` / `stop_survey`, `quality_report`, `search_channels`, `get_guide`.

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
