from __future__ import annotations

import copy
from unittest.mock import AsyncMock, patch

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.ha_iptv.const import CONF_ADB_ENTITY, DOMAIN

pytest_plugins = "pytest_homeassistant_custom_component"

STATE = {
    "v": 5, "app": "0.6", "ready": True, "configured": True, "server_time": 1_000_000, "power": "playing", "error": None,
    "groups": 3, "channels": 10, "region": "PL", "group": "PL| NEWS", "group_id": "g1",
    "channel": {"id": "c1", "num": 16, "name": "PL: NEWS ONE", "icon": "http://x/i.png", "group_id": "g1", "group": "PL| NEWS",
                "region": "PL", "fav": False, "claimed": "4K"},
    "tech": {"w": 1280, "h": 720, "fps": 50, "vcodec": "H.264", "acodec": "AAC", "ach": 2, "max_h": 720, "label": "HD 720p",
             "short": "HD", "mismatch": True, "verdict": "claims 4K"},
    "epg_state": "ok",
    "now": {"title": "Wiadomości", "desc": "News", "start": 999_000, "stop": 1_001_000},
    "next": {"title": "Pogoda", "desc": "", "start": 1_001_000, "stop": 1_002_000},
}
REGIONS = [{"id": "PL", "name": "PL", "groups": 2, "channels": 5}, {"id": "UK", "name": "UK", "groups": 1, "channels": 5}]
GROUPS = [{"id": "g1", "name": "PL| NEWS", "count": 3, "region": "PL"}, {"id": "g2", "name": "PL| SPORT", "count": 2, "region": "PL"},
          {"id": "g3", "name": "UK| NEWS", "count": 5, "region": "UK"}]
CHANNELS = [{**STATE["channel"], "picture": {"state": "black", "age": 60}}, {"id": "c2", "num": 17, "name": "PL: NEWS TWO", "icon": "", "group_id": "g1", "group": "PL| NEWS", "region": "PL", "fav": True,
                               "measured": {"w": 1920, "h": 1080, "fps": 25, "label": "FHD", "mismatch": False}}]


@pytest.fixture(autouse=True)
def _enable(enable_custom_integrations):
    yield


@pytest.fixture
def api():
    with patch("custom_components.ha_iptv.HaIptvApi") as cls, patch("custom_components.ha_iptv.config_flow.HaIptvApi", cls):
        a = cls.return_value
        async def _state(wait=None, timeout=25):
            if wait is None:
                return copy.deepcopy(STATE)
            await _hang()
        a.state = AsyncMock(side_effect=_state)
        a.regions = AsyncMock(return_value=REGIONS)
        a.groups = AsyncMock(return_value=GROUPS)
        a.channels = AsyncMock(return_value=CHANNELS)
        a.search = AsyncMock(return_value=CHANNELS[:1])
        a.epg = AsyncMock(return_value={"c1": [{"title": "Wiadomości", "desc": "", "start": 999_000, "stop": 1_001_000}]})
        a.quality = AsyncMock(return_value=[{**CHANNELS[0], "verdict": "mismatch"}, {**CHANNELS[1], "verdict": "ok"}])
        a.command = AsyncMock(return_value={"ok": True})
        a.sync = AsyncMock(return_value={"device": "aaaa", "name": "Fire TV", "epoch": "e1", "seq": 0, "entries": {}})
        a.sync_push = AsyncMock(return_value={"ok": True, "applied": 0})
        a.pair_request = AsyncMock(return_value={"ok": True, "id": "p1", "snonce": "5a5a5a5a5a5a5a5a", "name": "Bedroom"})
        a.pair_poll = AsyncMock(return_value={"status": "approved", "token": "tok123"})
        yield a


async def _hang():
    import asyncio
    await asyncio.sleep(3600)


@pytest.fixture
def entry(hass):
    e = MockConfigEntry(domain=DOMAIN, data={"host": "1.2.3.4", "port": 8765, "token": "t"}, options={CONF_ADB_ENTITY: "media_player.stick"},
                        unique_id="1.2.3.4:8765", title="HA IPTV")
    e.add_to_hass(hass)
    return e
