"""The hub of the sync: Home Assistant keeps the merged state and relays it between the sticks.

Each stick keeps `key -> (value, t)` entries (see the app's Sync.kt). The hub pulls what changed on a stick, merges it with
last-write-wins and pushes the result to every other stick that is reachable. A stick that was off gets everything when it
comes back, so the TV can be off while you edit favourites on the other one.
"""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.storage import Store

from .api import HaIptvError

if TYPE_CHECKING:
    from .coordinator import HaIptvCoordinator

_LOGGER = logging.getLogger(__name__)

STORE_VERSION = 1
FUTURE_SLACK_MS = 120_000
TOMBSTONE_TTL_MS = 90 * 86400_000
NP_PREFIX = "np:"


def merge_entries(store: dict[str, list], incoming: dict[str, Any], now_ms: int | None = None, skip: str | None = None) -> dict[str, list]:
    """Merge `incoming` into `store` (last write wins). Returns the entries that changed. Same rule as the app."""
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    horizon = now_ms + FUTURE_SLACK_MS
    changed: dict[str, list] = {}
    for key, ent in incoming.items():
        if key == skip or not isinstance(ent, (list, tuple)) or len(ent) < 2:
            continue
        value, t = str(ent[0]), min(int(ent[1]), horizon)
        old = store.get(key)
        newer = old is None or t > old[1] or (t == old[1] and value > old[0])
        if newer and not (old is None and value == ""):
            store[key] = [value, t]
            changed[key] = [value, t]
    return changed


class SyncHub:
    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self._store: Store = Store(hass, STORE_VERSION, "ha_iptv_sync")
        self.entries: dict[str, list] = {}
        self.cursors: dict[str, dict] = {}             # device id -> {"epoch", "seq"}
        self.coords: dict[str, HaIptvCoordinator] = {}  # entry id -> coordinator
        self.devices: dict[str, str] = {}               # device id -> entry id
        self._need_full: set[str] = set()
        self._lock = asyncio.Lock()
        self.last_sync: datetime | None = None

    # ------------------------------------------------------------------ storage
    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        self.entries = data.get("entries", {})
        self.cursors = data.get("cursors", {})
        self.devices = data.get("devices", {})
        cut = int(time.time() * 1000) - TOMBSTONE_TTL_MS
        self.entries = {k: v for k, v in self.entries.items() if not (v[0] == "" and v[1] < cut)}

    def _save(self) -> None:
        self._store.async_delay_save(lambda: {"entries": self.entries, "cursors": self.cursors, "devices": self.devices}, 5)

    # ------------------------------------------------------------------ registration
    def register(self, coord: HaIptvCoordinator) -> None:
        self.coords[coord.entry.entry_id] = coord
        self._need_full.add(coord.entry.entry_id)

    def unregister(self, coord: HaIptvCoordinator) -> None:
        self.coords.pop(coord.entry.entry_id, None)
        self._need_full.discard(coord.entry.entry_id)

    def mark_offline(self, coord: HaIptvCoordinator) -> None:
        self._need_full.add(coord.entry.entry_id)

    # ------------------------------------------------------------------ views
    def device_name(self, device_id: str) -> str | None:
        coord = self.coords.get(self.devices.get(device_id, ""))
        return coord.entry.title if coord else None

    def newest_now_playing(self, exclude_device: str | None = None) -> tuple[str, dict] | None:
        """The most recently updated np:<device> entry (what a stick is or was playing)."""
        import json
        best: tuple[int, str, dict] | None = None
        for key, (value, t) in self.entries.items():
            if not key.startswith(NP_PREFIX) or not value or key[len(NP_PREFIX):] == exclude_device:
                continue
            try:
                np = json.loads(value)
            except ValueError:
                continue
            if best is None or t > best[0]:
                best = (t, key[len(NP_PREFIX):], np)
        return (best[1], best[2]) if best else None

    # ------------------------------------------------------------------ exchange
    async def async_sync(self, coord: HaIptvCoordinator, state: dict | None = None) -> None:
        """Pull `coord`'s changes, merge, relay to the others; first time (or after it was off) also push everything to it."""
        eid = coord.entry.entry_id
        async with self._lock:
            try:
                await self._pull_and_relay(coord)
                if eid in self._need_full:
                    await self._push(coord, dict(self.entries))
                    self._need_full.discard(eid)
                    await self._send_config(coord)
            except HaIptvError as err:
                _LOGGER.debug("sync with %s failed: %s", coord.entry.title, err)
                self._need_full.add(eid)
                return
            self.last_sync = datetime.now(timezone.utc)

    async def _send_config(self, coord: HaIptvCoordinator) -> None:
        name = coord.device_name
        if name:
            await coord.api.command(cmd="config", name=name)

    async def _pull_and_relay(self, coord: HaIptvCoordinator) -> None:
        eid = coord.entry.entry_id
        known = self._dev(coord)
        meta = await coord.api.sync(self.cursors[known]["seq"] if known in self.cursors else 0)
        dev = meta.get("device")
        if not dev:
            return
        self.devices[dev] = eid
        cur = self.cursors.get(dev)
        if cur and cur.get("epoch") != meta.get("epoch") and cur.get("seq"):
            meta = await coord.api.sync(0)        # the stick's data was wiped: start over with it
            self._need_full.add(eid)
        changed = merge_entries(self.entries, meta.get("entries", {}))
        self.cursors[dev] = {"epoch": meta.get("epoch"), "seq": meta.get("seq", 0)}
        self._save()
        if not changed:
            return
        for oid, other in list(self.coords.items()):
            if oid == eid or not other.connected:
                continue
            try:
                await self._push(other, changed)
            except HaIptvError as err:
                _LOGGER.debug("relay to %s failed: %s", other.entry.title, err)
                self._need_full.add(oid)

    def _dev(self, coord: HaIptvCoordinator) -> str | None:
        eid = coord.entry.entry_id
        return next((d for d, e in self.devices.items() if e == eid), None)

    async def _push(self, coord: HaIptvCoordinator, entries: dict[str, list]) -> None:
        if entries:
            await coord.api.sync_push(entries)

    async def async_sync_all(self) -> None:
        for coord in list(self.coords.values()):
            if coord.connected:
                await self.async_sync(coord)


# ---------------------------------------------------------------------- continue on another TV
def item_from_state(st: dict | None) -> dict | None:
    """What a stick is playing right now, from its /api/state (exact position)."""
    if not st or st.get("power") in (None, "off", "idle", "background"):
        return None
    if v := st.get("vod"):
        return {"kind": v.get("kind", "movie"), "id": v["id"], "title": v.get("title"), "ext": v.get("ext"), "poster": v.get("poster"),
                "series": v.get("series"), "pos": v.get("pos", 0)}
    if ch := st.get("channel"):
        return {"kind": "live", "id": ch["id"], "title": ch.get("name"), "g": st.get("group_id")}
    return None


def item_from_np(np: dict | None) -> dict | None:
    """What a stick last published (works when it is off)."""
    if not np or not np.get("id"):
        return None
    if np.get("dur") and np.get("pos", 0) > np["dur"] * 0.95:
        return None                                    # it finished, nothing to continue
    return {k: np.get(k) for k in ("kind", "id", "title", "ext", "poster", "series", "pos", "g")}


def command_for(item: dict) -> dict:
    if item["kind"] == "live":
        cmd: dict[str, Any] = {"cmd": "play", "channel_id": item["id"]}
        if item.get("g"):
            cmd["group"] = item["g"]
        return cmd
    return {"cmd": "vod", "kind": item["kind"], "id": item["id"], "title": item.get("title") or "", "ext": item.get("ext") or "mkv",
            "poster": item.get("poster") or "", "series": item.get("series") or "", "pos_ms": int(item.get("pos") or 0)}


async def async_continue(hub: SyncHub, target: HaIptvCoordinator, source: HaIptvCoordinator | None = None, stop_source: bool = True) -> dict:
    """Start on `target` what `source` (default: whichever played most recently) is playing, from the same place."""
    from homeassistant.exceptions import HomeAssistantError

    tdev = hub._dev(target)
    np: dict | None = None
    if source is None:
        found = hub.newest_now_playing(exclude_device=tdev)
        if not found:
            raise HomeAssistantError("nothing to continue: no other TV has played anything yet")
        sdev, np = found
        source = hub.coords.get(hub.devices.get(sdev, ""))
    else:
        import json
        sdev = hub._dev(source)
        raw = (hub.entries.get(f"np:{sdev}") or [""])[0] if sdev else ""
        np = json.loads(raw) if raw else None
    if source is target:
        raise HomeAssistantError("source and target are the same TV")

    item = None
    if source is not None and source.connected:
        try:
            st = await source.api.state()
        except HaIptvError:
            st = None
        item = item_from_state(st)
        if item and stop_source:
            try:
                await source.api.command(cmd="stop")       # frees the single provider connection and saves the position
            except HaIptvError as err:
                _LOGGER.debug("could not stop %s: %s", source.entry.title, err)
            await asyncio.sleep(1.5)
    if item is None:
        item = item_from_np(np)
    if item is None:
        raise HomeAssistantError("nothing to continue on the other TV")
    return await target.async_command(**command_for(item)) | {"item": item.get("title")}
