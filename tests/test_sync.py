"""Sync hub: merge rules, relay between two fake sticks, catch-up of a stick that was off, continue-on."""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from homeassistant.exceptions import HomeAssistantError

from custom_components.ha_iptv.api import HaIptvConnectionError
from custom_components.ha_iptv.sync import (
    SyncHub, async_continue, command_for, item_from_np, item_from_state, merge_entries,
)

NOW = 1_700_000_000_000


# ------------------------------------------------------------------ merge rules (same rules as the app's SyncStore)
def test_newer_wins_older_ignored():
    s = {"fav:1": ["1", 100]}
    assert merge_entries(s, {"fav:1": ["0", 200]}, NOW) == {"fav:1": ["0", 200]}
    assert merge_entries(s, {"fav:1": ["1", 150]}, NOW) == {}
    assert s["fav:1"] == ["0", 200]


def test_tie_breaks_by_value_so_everyone_converges():
    a, b = {"k": ["x", 5]}, {"k": ["y", 5]}
    merge_entries(a, b, NOW)
    merge_entries(b, {"k": ["x", 5]}, NOW)
    assert a == b == {"k": ["y", 5]}


def test_tombstone_wins_when_newer_but_unknown_tombstone_is_not_stored():
    s = {"fav:1": ["1", 100]}
    merge_entries(s, {"fav:1": ["", 300], "fav:9": ["", 300]}, NOW)
    assert s["fav:1"] == ["", 300] and "fav:9" not in s


def test_clock_running_ahead_is_clamped():
    s: dict = {}
    merge_entries(s, {"k": ["a", NOW + 10 * 86400_000]}, NOW)
    assert s["k"][1] == NOW + 120_000
    # a second far-future write is clamped to the same instant, so it is a tie and the value decides (no one can pin a key forever)
    assert merge_entries(s, {"k": ["b", NOW + 200_000]}, NOW) == {"k": ["b", NOW + 120_000]}


def test_junk_is_ignored():
    s: dict = {}
    assert merge_entries(s, {"a": "nope", "b": [], "c": ["v"]}, NOW) == {}


# ------------------------------------------------------------------ fake sticks
class FakeStick:
    """Mirrors the app: entries, a sequence number per local change, an epoch, the same merge rule."""

    def __init__(self, device: str, title: str):
        self.device, self.epoch, self.seq = device, "e1", 0
        self.entries: dict[str, list] = {}
        self.seqs: dict[str, int] = {}
        self.up = True
        self.state_data: dict = {"power": "idle"}
        self.commands: list[dict] = []
        self.api = SimpleNamespace(sync=AsyncMock(side_effect=self._sync), sync_push=AsyncMock(side_effect=self._push),
                                   state=AsyncMock(side_effect=self._state), command=AsyncMock(side_effect=self._command))
        self.entry = SimpleNamespace(entry_id=f"entry_{device}", title=title)
        self.connected = True
        self.device_name = title
        self.async_command = AsyncMock(side_effect=self._async_command)

    def _check(self):
        if not self.up:
            raise HaIptvConnectionError("off")

    def edit(self, key, value, t):
        self.seq += 1
        self.entries[key] = [value, t]
        self.seqs[key] = self.seq

    async def _sync(self, since=0):
        self._check()
        return {"device": self.device, "name": self.entry.title, "epoch": self.epoch, "seq": self.seq,
                "entries": {k: v for k, v in self.entries.items() if self.seqs[k] > since}}

    async def _push(self, entries):
        self._check()
        for k in merge_entries(self.entries, {k: v for k, v in entries.items() if k != f"np:{self.device}"}, NOW):
            self.seq += 1
            self.seqs[k] = self.seq
        return {"ok": True}

    async def _state(self, *a, **k):
        self._check()
        return self.state_data

    async def _command(self, **cmd):
        self._check()
        self.commands.append(cmd)
        if cmd.get("cmd") == "stop":
            self.state_data = {"power": "idle"}
        return {"ok": True}

    async def _async_command(self, **cmd):
        return await self._command(**cmd)


@pytest.fixture
async def hub(hass, hass_storage):
    h = SyncHub(hass)
    await h.async_load()
    return h


def _pair(hub):
    a, b = FakeStick("aaaa", "Living room"), FakeStick("bbbb", "Bedroom")
    for s in (a, b):
        hub.register(s)
    return a, b


async def test_edit_on_one_stick_reaches_the_other(hub):
    a, b = _pair(hub)
    await hub.async_sync(a); await hub.async_sync(b)          # first contact
    a.edit("fav:42", "1", NOW)
    a.edit("order:groups", '["g2","g1"]', NOW)
    await hub.async_sync(a)
    assert b.entries["fav:42"] == ["1", NOW] and b.entries["order:groups"][0] == '["g2","g1"]'


async def test_no_echo_loop(hub):
    a, b = _pair(hub)
    a.edit("fav:1", "1", NOW)
    await hub.async_sync(a); await hub.async_sync(b)
    b.api.sync_push.reset_mock(); a.api.sync_push.reset_mock()
    await hub.async_sync(a); await hub.async_sync(b)           # echoes come back but change nothing
    b.api.sync_push.assert_not_called(); a.api.sync_push.assert_not_called()


async def test_stick_that_was_off_catches_up_with_everything(hub):
    a, b = _pair(hub)
    await hub.async_sync(a); await hub.async_sync(b)
    b.up = False; b.connected = False; hub.mark_offline(b)
    a.edit("fav:7", "1", NOW); a.edit("vod:9", "60000,600000", NOW)
    await hub.async_sync(a)
    assert "fav:7" not in b.entries
    b.up = True; b.connected = True
    await hub.async_sync(b)
    assert b.entries["fav:7"] == ["1", NOW] and b.entries["vod:9"][0] == "60000,600000"
    b.up = True


async def test_concurrent_edits_both_survive_and_conflicts_resolve_by_time(hub):
    a, b = _pair(hub)
    await hub.async_sync(a); await hub.async_sync(b)
    a.edit("fav:1", "1", NOW + 1); b.edit("fav:2", "1", NOW + 2)          # different channels: union
    a.edit("order:regions", '["PL"]', NOW + 10); b.edit("order:regions", '["UK"]', NOW + 20)   # same key: newest wins
    await hub.async_sync(a); await hub.async_sync(b)
    for s in (a, b):
        assert s.entries["fav:1"][0] == "1" and s.entries["fav:2"][0] == "1"
        assert s.entries["order:regions"][0] == '["UK"]'


async def test_removed_favourite_propagates_as_tombstone(hub):
    a, b = _pair(hub)
    a.edit("fav:1", "1", NOW); await hub.async_sync(a); await hub.async_sync(b)
    b.edit("fav:1", "", NOW + 5); await hub.async_sync(b)
    assert a.entries["fav:1"] == ["", NOW + 5]


async def test_wiped_stick_is_started_over(hub):
    a, b = _pair(hub)
    a.edit("fav:1", "1", NOW); await hub.async_sync(a); await hub.async_sync(b)
    b.epoch, b.entries, b.seqs, b.seq = "e2", {}, {}, 0        # app data cleared
    await hub.async_sync(b)
    assert b.entries["fav:1"] == ["1", NOW]


async def test_name_is_sent_when_a_stick_connects(hub):
    a, b = _pair(hub)
    await hub.async_sync(a)
    a.api.command.assert_any_call(cmd="config", name="Living room")


async def test_hub_state_survives_restart(hass, hass_storage):
    h = SyncHub(hass); await h.async_load()
    a = FakeStick("aaaa", "Living room"); h.register(a)
    a.edit("fav:1", "1", NOW); await h.async_sync(a)
    await h._store.async_save({"entries": h.entries, "cursors": h.cursors, "devices": h.devices})
    h2 = SyncHub(hass); await h2.async_load()
    assert h2.entries["fav:1"] == ["1", NOW] and h2.devices["aaaa"] == "entry_aaaa"


# ------------------------------------------------------------------ continue on another TV
def test_item_from_state_film_episode_channel_idle():
    film = {"power": "playing", "vod": {"kind": "movie", "id": "5", "title": "Film", "ext": "mkv", "poster": "p", "pos": 61000}}
    assert command_for(item_from_state(film)) == {"cmd": "vod", "kind": "movie", "id": "5", "title": "Film", "ext": "mkv", "poster": "p", "series": "", "pos_ms": 61000}
    ep = {"power": "paused", "vod": {"kind": "episode", "id": "9", "title": "S1E2", "ext": "mp4", "series": "77", "pos": 5}}
    assert command_for(item_from_state(ep))["series"] == "77"
    live = {"power": "playing", "channel": {"id": "c1", "name": "News"}, "group_id": "g1"}
    assert command_for(item_from_state(live)) == {"cmd": "play", "channel_id": "c1", "group": "g1"}
    assert item_from_state({"power": "idle", "channel": {"id": "c1"}}) is None
    assert item_from_state(None) is None


def test_finished_item_is_not_continued():
    assert item_from_np({"kind": "movie", "id": "1", "pos": 960, "dur": 1000}) is None
    assert item_from_np({"kind": "movie", "id": "1", "pos": 100, "dur": 1000})["pos"] == 100


async def test_continue_takes_exact_position_and_stops_the_source(hub):
    a, b = _pair(hub)
    a.state_data = {"power": "playing", "vod": {"kind": "movie", "id": "5", "title": "Film", "ext": "mkv", "pos": 754000}}
    a.edit("np:aaaa", json.dumps({"kind": "movie", "id": "5", "title": "Film", "pos": 700000, "dur": 6000000, "state": "playing"}), NOW)
    await hub.async_sync(a)
    await async_continue(hub, b)
    assert {"cmd": "stop"} in a.commands
    cmd = b.async_command.await_args.kwargs
    assert cmd["cmd"] == "vod" and cmd["id"] == "5" and cmd["pos_ms"] == 754000      # live position, not the 700000 from the entry


async def test_continue_from_a_tv_that_is_off_uses_what_it_published(hub):
    a, b = _pair(hub)
    a.edit("np:aaaa", json.dumps({"kind": "episode", "id": "9", "title": "S1E2", "ext": "mkv", "series": "77", "pos": 123000, "dur": 3000000, "state": "stopped"}), NOW)
    await hub.async_sync(a)
    a.up = False; a.connected = False
    await async_continue(hub, b)
    cmd = b.async_command.await_args.kwargs
    assert cmd["kind"] == "episode" and cmd["series"] == "77" and cmd["pos_ms"] == 123000


async def test_continue_picks_the_most_recent_of_several(hub):
    a, b = _pair(hub)
    c = FakeStick("cccc", "Kitchen"); hub.register(c)
    a.edit("np:aaaa", json.dumps({"kind": "live", "id": "old", "title": "Old", "state": "stopped"}), NOW)
    c.edit("np:cccc", json.dumps({"kind": "live", "id": "new", "title": "New", "g": "g3", "state": "stopped"}), NOW + 5)
    await hub.async_sync(a); await hub.async_sync(c)
    a.up = c.up = False; a.connected = c.connected = False
    await async_continue(hub, b)
    assert b.async_command.await_args.kwargs == {"cmd": "play", "channel_id": "new", "group": "g3"}


async def test_continue_with_nothing_or_itself_is_an_error(hub):
    a, b = _pair(hub)
    with pytest.raises(HomeAssistantError, match="nothing to continue"):
        await async_continue(hub, b)
    with pytest.raises(HomeAssistantError, match="same TV"):
        await async_continue(hub, a, a)
