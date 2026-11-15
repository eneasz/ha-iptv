"""Programme watches (tune in when a show starts) and the sleep timer."""
from __future__ import annotations

import logging
import time
import uuid
from typing import Any

from homeassistant.core import CALLBACK_TYPE, HomeAssistant, callback
from homeassistant.helpers.event import async_call_later, async_track_time_interval
from homeassistant.helpers.storage import Store
from datetime import timedelta

from .api import HaIptvError
from .const import DOMAIN, EVENT_WATCH_TRIGGERED

_LOGGER = logging.getLogger(__name__)
GUIDE_TTL = 1200


class Scheduler:
    def __init__(self, hass: HomeAssistant, coordinator) -> None:
        self.hass = hass
        self.c = coordinator
        self._store: Store = Store(hass, 1, f"{DOMAIN}.{coordinator.entry.entry_id}")
        self.watches: list[dict[str, Any]] = []
        self._fired: dict[str, float] = {}
        self._unsub: CALLBACK_TYPE | None = None
        self._sleep: CALLBACK_TYPE | None = None
        self.sleep_at: float | None = None
        self._guide: dict[str, list[dict]] = {}
        self._guide_key = ""
        self._guide_at = 0.0

    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        self.watches = data.get("watches", [])
        self._fired = data.get("fired", {})

    def _save(self) -> None:
        self._store.async_delay_save(lambda: {"watches": self.watches, "fired": self._fired}, 1)

    def start(self) -> None:
        self._unsub = async_track_time_interval(self.hass, self._tick, timedelta(seconds=60))

    def stop(self) -> None:
        if self._unsub:
            self._unsub()
        if self._sleep:
            self._sleep()

    # ------------------------------------------------------------ watches
    def add_watch(self, title: str, *, channel_id: str | None, group: str | None, action: str, lead: int, once: bool) -> dict:
        w = {"id": uuid.uuid4().hex[:8], "title": title, "channel_id": channel_id, "group": group,
             "action": action, "lead": lead, "once": once}
        self.watches.append(w)
        self._save()
        self._guide_at = 0
        return w

    def remove_watch(self, wid: str) -> bool:
        n = len(self.watches)
        self.watches = [w for w in self.watches if w["id"] != wid and w["title"] != wid]
        self._save()
        return len(self.watches) < n

    async def _channels_for(self, w: dict) -> list[str]:
        if w.get("channel_id"):
            return [w["channel_id"]]
        gid = w.get("group") or "__fav"
        return [x["id"] for x in await self.c.async_channels(gid)][:60]

    async def _guide_for(self, ids: list[str]) -> dict[str, list[dict]]:
        key = ",".join(sorted(ids))
        if key != self._guide_key or time.monotonic() - self._guide_at > GUIDE_TTL:
            self._guide = await self.c.api.epg(ids, 30)
            self._guide_key, self._guide_at = key, time.monotonic()
        return self._guide

    async def _tick(self, _now) -> None:
        if not self.watches or not self.c.connected:
            return
        now = time.time()
        self._fired = {k: v for k, v in self._fired.items() if v > now - 86400}
        for w in list(self.watches):
            try:
                ids = await self._channels_for(w)
                if not ids:
                    continue
                guide = await self._guide_for(ids)
            except HaIptvError as err:
                _LOGGER.debug("watch scan failed: %s", err)
                continue
            needle = w["title"].lower()
            for cid, progs in guide.items():
                for p in progs or []:
                    if needle not in (p["title"] or "").lower():
                        continue
                    if not (p["start"] - w["lead"] * 60 <= now < p["stop"]):
                        continue
                    key = f"{w['id']}:{cid}:{p['start']}"
                    if key in self._fired:
                        continue
                    self._fired[key] = now
                    await self._trigger(w, cid, p)
                    if w["once"]:
                        self.remove_watch(w["id"])
                    self._save()
                    break
                else:
                    continue
                break

    async def _trigger(self, w: dict, cid: str, p: dict) -> None:
        chan = next((x for x in self.c._chan_cache.get(w.get("group") or "__fav", []) if x["id"] == cid), {})
        self.hass.bus.async_fire(EVENT_WATCH_TRIGGERED, {
            "entry_id": self.c.entry.entry_id, "watch_id": w["id"], "watch": w["title"], "channel_id": cid,
            "channel": chan.get("name"), "title": p["title"], "start": p["start"], "stop": p["stop"], "action": w["action"]})
        if w["action"] == "tune":
            try:
                await self.c.async_command(cmd="play", channel_id=cid)
            except Exception as err:  # noqa: BLE001
                _LOGGER.warning("watch '%s' could not tune: %s", w["title"], err)

    # ------------------------------------------------------------ sleep timer
    def set_sleep(self, minutes: float, *, power_off_tv: bool) -> None:
        if self._sleep:
            self._sleep()
            self._sleep = None
        self.sleep_at = None
        if minutes <= 0:
            return
        self.sleep_at = time.time() + minutes * 60

        @callback
        def _fire(_now) -> None:
            self._sleep, self.sleep_at = None, None
            self.hass.async_create_task(self._sleep_now(power_off_tv))

        self._sleep = async_call_later(self.hass, minutes * 60, _fire)

    async def _sleep_now(self, power_off_tv: bool) -> None:
        try:
            await self.c.api.command(cmd="stop")
            await self.c.api.command(cmd="home")
        except HaIptvError:
            pass
        if (power_off_tv or self.c.turn_off_tv) and (tv := self.c.tv_entity):
            await self.hass.services.async_call("media_player", "turn_off", {"entity_id": tv})
