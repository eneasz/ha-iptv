"""Push coordinator: long-polls the app so HA state follows the TV in real time."""
from __future__ import annotations

import asyncio
import logging
import shlex
from datetime import timedelta
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .api import HaIptvApi, HaIptvAuthError, HaIptvConnectionError, HaIptvError
from .const import (
    APP_COMPONENT, CONF_ADB_ENTITY, CONF_DEVICE_NAME, CONF_TURN_OFF_TV, CONF_TV_ENTITY, DOMAIN, EVENT_CHANNEL_CHANGED, FRONT,
    EVENT_PROGRAMME_STARTED, EVENT_QUALITY_MISMATCH, EVENT_STREAM_ERROR, LAUNCHABLE,
)

_LOGGER = logging.getLogger(__name__)


def intent_command(cmd: dict[str, Any]) -> str:
    """Shell command that launches the app and hands it a command via intent extras."""
    parts = [f"am start -n {APP_COMPONENT}"]
    mapping = {"cmd": "cmd", "query": "query", "channel_id": "channel_id", "group": "group", "region": "region", "id": "id"}
    for key, extra in mapping.items():
        if cmd.get(key) not in (None, ""):
            parts.append(f"--es {extra} {shlex.quote(str(cmd[key]))}")
    if "delta" in cmd:
        parts.append(f"--ei delta {int(cmd['delta'])}")
    if cmd.get("play"):
        parts.append("--ez play true")
    return " ".join(parts)


class HaIptvCoordinator(DataUpdateCoordinator[dict]):
    """State comes from a long-poll loop; a slow fallback poll covers a dead loop."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry, api: HaIptvApi) -> None:
        super().__init__(hass, _LOGGER, name=DOMAIN, config_entry=entry, update_interval=timedelta(seconds=60))
        self.api = api
        self.entry = entry
        self.connected = False
        self._version = -1
        self._task: asyncio.Task | None = None
        self.regions: list[dict] = []
        self.groups: list[dict] = []
        self._chan_cache: dict[str, list[dict]] = {}
        self.guide: dict[str, list[dict]] = {}
        self.guide_channels: dict[str, dict] = {}
        self.scheduler: Any = None
        self.hub: Any = None                  # SyncHub, set by __init__
        self._last_seq: Any = None
        self._sync_task: asyncio.Task | None = None
        self._sync_again = False

    @property
    def device_name(self) -> str | None:
        return (self.entry.options.get(CONF_DEVICE_NAME) or "").strip() or None

    # -------------------------------------------------------------- sync with the other sticks
    def request_sync(self) -> None:
        """Pull this stick's changes into the hub and relay them (coalesces bursts)."""
        if not self.hub:
            return
        if self._sync_task and not self._sync_task.done():
            self._sync_again = True
            return
        self._sync_task = self.entry.async_create_background_task(self.hass, self._sync_loop(), f"{DOMAIN}_sync")

    async def _sync_loop(self) -> None:
        while True:
            self._sync_again = False
            await self.hub.async_sync(self)
            self.async_update_listeners()
            if not self._sync_again:
                return

    # -------------------------------------------------------------- options
    @property
    def adb_entity(self) -> str | None:
        return self.entry.options.get(CONF_ADB_ENTITY) or self.entry.data.get(CONF_ADB_ENTITY)

    @property
    def tv_entity(self) -> str | None:
        return self.entry.options.get(CONF_TV_ENTITY) or self.entry.data.get(CONF_TV_ENTITY)

    @property
    def turn_off_tv(self) -> bool:
        return bool(self.entry.options.get(CONF_TURN_OFF_TV, False))

    # -------------------------------------------------------------- state
    async def _async_update_data(self) -> dict:
        try:
            data = await self.api.state()
        except HaIptvAuthError:
            self.connected = False
            return {"power": "off", "offline": True, "error": "auth"}
        except HaIptvError as err:
            self.connected = False
            return self._offline(err)
        self.connected = True
        return data

    def _offline(self, err: Exception) -> dict:
        """Not reachable is a normal condition (TV off): report it as data, not as a failure."""
        return {"power": "off", "offline": True, "error": str(err)}

    def start_push(self) -> None:
        if self._task is None:
            self._task = self.entry.async_create_background_task(self.hass, self._push_loop(), f"{DOMAIN}_push")

    async def async_shutdown(self) -> None:
        if self._task:
            self._task.cancel()
            self._task = None
        await super().async_shutdown()

    async def _push_loop(self) -> None:
        while True:
            try:
                data = await self.api.state(wait=self._version, timeout=25)
            except asyncio.CancelledError:
                raise
            except HaIptvAuthError:
                self.connected = False
                self.async_set_updated_data(self._offline(HaIptvAuthError("token rejected")))
                await asyncio.sleep(60)
                continue
            except HaIptvError as err:
                if self.hub:
                    self.hub.mark_offline(self)
                if self.connected or self.data is None:
                    self.connected = False
                    self.async_set_updated_data(self._offline(err))
                self._version = -1
                await asyncio.sleep(10)
                continue
            if not self.connected:
                self.connected = True
                self._chan_cache.clear()
                self.hass.async_create_task(self.async_refresh_catalog())
                self._last_seq = None
            self._version = data.get("v", -1)
            self._emit_events(self.data or {}, data)
            self.async_set_updated_data(data)
            seq = (data.get("sync") or {}).get("seq")
            if seq is not None and seq != self._last_seq:
                self._last_seq = seq
                self.request_sync()

    # -------------------------------------------------------------- events
    def _emit_events(self, old: dict, new: dict) -> None:
        eid = self.entry.entry_id
        oc, nc = (old.get("channel") or {}), (new.get("channel") or {})
        if nc and nc.get("id") != oc.get("id"):
            self.hass.bus.async_fire(EVENT_CHANNEL_CHANGED, {
                "entry_id": eid, "channel": nc.get("name"), "channel_id": nc.get("id"), "number": nc.get("num"),
                "group": new.get("group"), "region": new.get("region")})
        on, nn = (old.get("now") or {}), (new.get("now") or {})
        if nn and nn.get("start") != on.get("start") and nc.get("id") == oc.get("id") or (nn and not on and nc):
            self.hass.bus.async_fire(EVENT_PROGRAMME_STARTED, {
                "entry_id": eid, "channel": nc.get("name"), "title": nn.get("title"), "start": nn.get("start"),
                "stop": nn.get("stop"), "description": nn.get("desc")})
        if (new.get("tech") or {}).get("mismatch") and not (old.get("tech") or {}).get("mismatch"):
            t = new["tech"]
            self.hass.bus.async_fire(EVENT_QUALITY_MISMATCH, {
                "entry_id": eid, "channel": nc.get("name"), "claimed": nc.get("claimed"), "actual": t.get("short"),
                "resolution": f"{t.get('w')}x{t.get('h')}", "verdict": t.get("verdict")})
        if new.get("error") and not old.get("error"):
            self.hass.bus.async_fire(EVENT_STREAM_ERROR, {"entry_id": eid, "channel": nc.get("name"), "error": new.get("error")})
        pic = new.get("picture", "ok")
        if pic != "ok" and old.get("picture", "ok") == "ok":
            self.hass.bus.async_fire(EVENT_STREAM_ERROR, {"entry_id": eid, "channel": nc.get("name"), "error": f"{pic}_picture"})

    # -------------------------------------------------------------- catalogue
    async def async_refresh_catalog(self) -> None:
        try:
            self.regions = await self.api.regions()
            self.groups = await self.api.groups()
        except HaIptvError as err:
            _LOGGER.debug("catalogue refresh failed: %s", err)
            return
        self.async_update_listeners()

    async def async_channels(self, group_id: str) -> list[dict]:
        if group_id not in self._chan_cache:
            self._chan_cache[group_id] = await self.api.channels(group_id)
        return self._chan_cache[group_id]

    def invalidate_channels(self) -> None:
        self._chan_cache.clear()

    # -------------------------------------------------------------- commands (with ADB wake-up)
    async def _call(self, domain: str, service: str, data: dict) -> None:
        await self.hass.services.async_call(domain, service, data, blocking=True)

    async def async_launch_app(self, cmd: dict[str, Any] | None = None) -> None:
        """Wake the TV and start the app (optionally carrying a command in the launch intent)."""
        if not self.adb_entity:
            raise HomeAssistantError("HA IPTV is not reachable and no ADB media_player is configured to launch it")
        tv = self.tv_entity
        if tv and (st := self.hass.states.get(tv)) and st.state in ("off", "standby"):
            await self._call("media_player", "turn_on", {"entity_id": tv})
            await asyncio.sleep(6)
        await self._call("androidtv", "adb_command", {"entity_id": self.adb_entity, "command": intent_command(cmd or {})})

    async def async_wait_reachable(self, tries: int = 20) -> bool:
        for _ in range(tries):
            try:
                await self.api.state()
                return True
            except HaIptvError:
                await asyncio.sleep(1.5)
        return False

    async def async_command(self, **cmd: Any) -> dict:
        """Send a command; if the app is closed or in the background, bring it up over ADB first."""
        power = (self.data or {}).get("power")
        if cmd.get("cmd") in FRONT and self.adb_entity and power in (None, "off", "background"):
            await self.async_launch_app(None)          # these carry too much for a launch intent: open the app, then send
            if not await self.async_wait_reachable():
                raise HomeAssistantError("launched the app but it did not become reachable")
            try:
                return await self.api.command(**cmd)
            except HaIptvError as err:
                raise HomeAssistantError(str(err)) from err
        launch = self.adb_entity and cmd.get("cmd") in LAUNCHABLE and power in (None, "off", "background")
        if launch:
            await self.async_launch_app(cmd)
            if await self.async_wait_reachable():
                await self.async_request_refresh()
                return {"ok": True, "launched": True}
            raise HomeAssistantError("launched the app but it did not become reachable")
        try:
            res = await self.api.command(**cmd)
        except HaIptvConnectionError:
            if not self.adb_entity:
                raise HomeAssistantError("HA IPTV is not reachable (app closed or TV off) and no ADB media_player is configured") from None
            await self.async_launch_app(cmd if cmd.get("cmd") in LAUNCHABLE else None)
            if not await self.async_wait_reachable():
                raise HomeAssistantError("launched the app but it did not become reachable") from None
            if cmd.get("cmd") in LAUNCHABLE:
                return {"ok": True, "launched": True}
            res = await self.api.command(**cmd)
        except HaIptvError as err:
            raise HomeAssistantError(str(err)) from err
        return res
