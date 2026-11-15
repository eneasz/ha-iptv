"""Calendar of upcoming programmes on favourite channels (and the current channel)."""
from __future__ import annotations

import time
from datetime import datetime, timezone

from homeassistant.components.calendar import CalendarEntity, CalendarEvent
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .api import HaIptvError
from .entity import HaIptvEntity

GUIDE_TTL = 1800


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([Guide(entry.runtime_data)])


class Guide(HaIptvEntity, CalendarEntity):
    _attr_translation_key = "guide"
    _attr_icon = "mdi:television-guide"

    def __init__(self, c) -> None:
        super().__init__(c, "guide")
        self._loaded = 0.0
        self._events: list[CalendarEvent] = []

    @property
    def event(self) -> CalendarEvent | None:
        now = datetime.now(timezone.utc)
        return next((e for e in self._events if e.start <= now < e.end), None)

    async def _load(self) -> None:
        if time.monotonic() - self._loaded < GUIDE_TTL and self._events:
            return
        c = self.coordinator
        try:
            favs = await c.api.channels("__fav")
            chans = {x["id"]: x for x in favs}
            if (cur := (c.data or {}).get("channel")):
                chans.setdefault(cur["id"], cur)
            if not chans:
                return
            guide = await c.api.epg(list(chans)[:40], 12)
        except HaIptvError:
            return
        ev: list[CalendarEvent] = []
        for cid, progs in guide.items():
            for p in progs or []:
                ev.append(CalendarEvent(
                    start=datetime.fromtimestamp(p["start"], timezone.utc), end=datetime.fromtimestamp(p["stop"], timezone.utc),
                    summary=f"{p['title']} · {chans[cid]['name']}", description=p.get("desc") or "", location=chans[cid]["name"],
                    uid=f"{cid}:{p['start']}"))
        ev.sort(key=lambda e: e.start)
        self._events, self._loaded = ev, time.monotonic()

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.hass.async_create_task(self._refresh_state())

    async def _refresh_state(self) -> None:
        await self._load()
        self.async_write_ha_state()

    async def async_get_events(self, hass: HomeAssistant, start_date: datetime, end_date: datetime) -> list[CalendarEvent]:
        await self._load()
        return [e for e in self._events if e.end > start_date and e.start < end_date]
