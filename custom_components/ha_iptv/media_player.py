"""media_player: the TV channel as seen by HA, with a browsable region > group > channel tree."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from homeassistant.components.media_player import (
    BrowseMedia, MediaClass, MediaPlayerEntity, MediaPlayerEntityFeature, MediaPlayerState, MediaType, async_process_play_media_url,
)
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import HaIptvEntity

FEATURES = (
    MediaPlayerEntityFeature.TURN_ON | MediaPlayerEntityFeature.TURN_OFF | MediaPlayerEntityFeature.PLAY
    | MediaPlayerEntityFeature.PAUSE | MediaPlayerEntityFeature.STOP | MediaPlayerEntityFeature.NEXT_TRACK
    | MediaPlayerEntityFeature.PREVIOUS_TRACK | MediaPlayerEntityFeature.PLAY_MEDIA
    | MediaPlayerEntityFeature.BROWSE_MEDIA | MediaPlayerEntityFeature.SELECT_SOURCE
)


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([HaIptvPlayer(entry.runtime_data)])


class HaIptvPlayer(HaIptvEntity, MediaPlayerEntity):
    _attr_name = None
    _attr_supported_features = FEATURES
    _attr_media_content_type = MediaType.CHANNEL

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator, "player")
        self._pos_stamp: datetime | None = None

    @property
    def state(self) -> MediaPlayerState:
        return {
            "playing": MediaPlayerState.PLAYING, "buffering": MediaPlayerState.BUFFERING,
            "paused": MediaPlayerState.PAUSED, "idle": MediaPlayerState.IDLE, "background": MediaPlayerState.IDLE,
        }.get(self.d.get("power", "off"), MediaPlayerState.OFF)

    @property
    def _ch(self) -> dict:
        return self.d.get("channel") or {}

    @property
    def _vod(self) -> dict:
        return self.d.get("vod") or {}

    @property
    def media_content_type(self) -> str:
        return {"movie": MediaType.MOVIE, "episode": MediaType.TVSHOW}.get(self._vod.get("kind"), MediaType.CHANNEL)

    @property
    def media_channel(self) -> str | None:
        return None if self._vod else self._ch.get("name")

    @property
    def media_content_id(self) -> str | None:
        if self._vod:
            return f"{self._vod.get('kind', 'movie')}:{self._vod['id']}"
        return f"channel:{self._ch['id']}" if self._ch else None

    @property
    def media_title(self) -> str | None:
        if self._vod:
            return self._vod.get("title")
        return (self.d.get("now") or {}).get("title") or self._ch.get("name")

    @property
    def media_series_title(self) -> str | None:
        return None if self._vod else self._ch.get("name")

    @property
    def media_image_url(self) -> str | None:
        if self._vod:
            return self._vod.get("poster") or None
        return self._ch.get("icon") or None

    @property
    def media_image_remotely_accessible(self) -> bool:
        return True

    @property
    def media_duration(self) -> int | None:
        if self._vod:
            return int(self._vod["dur"] / 1000) if self._vod.get("dur") else None
        n = self.d.get("now")
        return int(n["stop"] - n["start"]) if n else None

    @property
    def media_position(self) -> int | None:
        if self._vod:
            return int(self._vod.get("pos", 0) / 1000)
        n = self.d.get("now")
        return max(0, int(self.d.get("server_time", 0) - n["start"])) if n else None

    @property
    def media_position_updated_at(self) -> datetime | None:
        return self._pos_stamp if (self.d.get("now") or self._vod) else None

    @property
    def source(self) -> str | None:
        return self.d.get("region")

    @property
    def source_list(self) -> list[str]:
        return [r["name"] for r in self.coordinator.regions]

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        d = self.d
        nxt, tech = d.get("next") or {}, d.get("tech") or {}
        return {
            "channel_number": self._ch.get("num"), "group": d.get("group"), "region": d.get("region"),
            "next_title": nxt.get("title"), "next_start": nxt.get("start"),
            "programme_description": (d.get("now") or {}).get("desc"),
            "resolution": f"{tech['w']}x{tech['h']}" if tech else None, "quality": tech.get("short"),
            "claimed_quality": self._ch.get("claimed"), "quality_mismatch": tech.get("mismatch"),
            "video_codec": tech.get("vcodec"), "audio_codec": tech.get("acodec"), "favourite": self._ch.get("fav"),
            "stream_error": d.get("error"), "picture": d.get("picture", "ok"), "survey": d.get("survey"),
        }

    def _handle_coordinator_update(self) -> None:
        self._pos_stamp = datetime.now(timezone.utc)
        super()._handle_coordinator_update()

    # ----------------------------------------------------------- control
    async def async_turn_on(self) -> None:
        await self.coordinator.async_command(cmd="resume" if self.d.get("power") not in (None, "off", "background") else "info")

    async def async_turn_off(self) -> None:
        if self.d.get("power") not in (None, "off"):
            await self.coordinator.api.command(cmd="stop")
            await self.coordinator.api.command(cmd="home")
        if self.coordinator.turn_off_tv and (tv := self.coordinator.tv_entity):
            await self.hass.services.async_call("media_player", "turn_off", {"entity_id": tv}, blocking=True)

    async def async_media_play(self) -> None:
        await self.coordinator.async_command(cmd="resume")

    async def async_media_pause(self) -> None:
        await self.coordinator.api.command(cmd="pause")

    async def async_media_stop(self) -> None:
        await self.coordinator.api.command(cmd="stop")

    async def async_media_next_track(self) -> None:
        await self.coordinator.async_command(cmd="zap", delta=1)

    async def async_media_previous_track(self) -> None:
        await self.coordinator.async_command(cmd="zap", delta=-1)

    async def async_select_source(self, source: str) -> None:
        await self.coordinator.async_command(cmd="region", region=source)

    async def async_play_media(self, media_type: str, media_id: str, **kwargs: Any) -> None:
        kind, _, val = media_id.partition(":")
        if kind == "channel" and val:
            await self.coordinator.async_command(cmd="play", channel_id=val)
        elif kind == "group" and val:
            await self.coordinator.async_command(cmd="group", group=val, play=True)
        elif kind == "region" and val:
            await self.coordinator.async_command(cmd="region", region=val, play=True)
        else:
            await self.coordinator.async_command(cmd="play", query=media_id)

    # ----------------------------------------------------------- browsing
    async def async_browse_media(self, media_content_type: str | None = None, media_content_id: str | None = None) -> BrowseMedia:
        c = self.coordinator
        kind, _, val = (media_content_id or "").partition(":")
        if not c.regions:
            await c.async_refresh_catalog()
        if kind == "region":
            return BrowseMedia(
                media_class=MediaClass.DIRECTORY, media_content_id=media_content_id, media_content_type="region", title=val,
                can_play=False, can_expand=True,
                children=[_group(g) for g in c.groups if g.get("region") == val or (val.startswith("★") and g["id"] == "__fav")])
        if kind == "group":
            try:
                chans = await c.async_channels(val)
            except Exception as err:  # noqa: BLE001
                raise HomeAssistantError(f"cannot list channels: {err}") from err
            title = next((g["name"] for g in c.groups if g["id"] == val), val)
            return BrowseMedia(
                media_class=MediaClass.DIRECTORY, media_content_id=media_content_id, media_content_type="group", title=title,
                can_play=False, can_expand=True, children=[_channel(x) for x in chans])
        return BrowseMedia(
            media_class=MediaClass.DIRECTORY, media_content_id="", media_content_type="root", title="HA IPTV",
            can_play=False, can_expand=True,
            children=[BrowseMedia(
                media_class=MediaClass.DIRECTORY, media_content_id=f"region:{r['id']}", media_content_type="region",
                title=f"{r['name']}  ({r['groups']} groups)", can_play=False, can_expand=True) for r in c.regions])


def _group(g: dict) -> BrowseMedia:
    name = g["name"].split("|", 1)[-1].strip() or g["name"]
    return BrowseMedia(media_class=MediaClass.DIRECTORY, media_content_id=f"group:{g['id']}", media_content_type="group",
                       title=f"{name}  ({g['count']})", can_play=True, can_expand=True)


def _channel(c: dict) -> BrowseMedia:
    tag = ""
    if (m := c.get("measured")):
        tag = f"  [{m['label']}{' ⚠' if m.get('mismatch') else ''}]"
    if (p := c.get("picture")) and p.get("state") in ("black", "error") and p.get("age", 0) < 6 * 3600:
        tag += "  [OFF AIR]"
    return BrowseMedia(media_class=MediaClass.CHANNEL, media_content_id=f"channel:{c['id']}", media_content_type=MediaType.CHANNEL,
                       title=f"{'★ ' if c.get('fav') else ''}{c['name']}{tag}", can_play=True, can_expand=False,
                       thumbnail=c.get("icon") or None)
