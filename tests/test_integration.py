from __future__ import annotations

import copy

import pytest
from homeassistant.components.media_player import MediaPlayerState
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.setup import async_setup_component

from custom_components.ha_iptv.api import HaIptvAuthError, HaIptvConnectionError
from custom_components.ha_iptv.const import DOMAIN
from custom_components.ha_iptv.coordinator import intent_command

from .conftest import STATE, _hang

PLAYER = "media_player.ha_iptv"


async def _setup(hass, entry):
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()


async def test_entities(hass: HomeAssistant, api, entry):
    await _setup(hass, entry)
    st = hass.states.get(PLAYER)
    assert st.state == MediaPlayerState.PLAYING
    assert st.attributes["media_title"] == "Wiadomości"
    assert st.attributes["media_channel"] == "PL: NEWS ONE"
    assert st.attributes["media_duration"] == 2000 and st.attributes["media_position"] == 1000
    assert st.attributes["next_title"] == "Pogoda" and st.attributes["resolution"] == "1280x720"
    assert st.attributes["source"] == "PL" and st.attributes["source_list"] == ["PL", "UK"]
    assert hass.states.get("binary_sensor.ha_iptv_quality_mismatch").state == "on"
    assert hass.states.get("binary_sensor.ha_iptv_app_reachable").state == "on"
    assert hass.states.get("sensor.ha_iptv_now_playing").state == "Wiadomości"
    assert hass.states.get("sensor.ha_iptv_measured_quality").state == "HD"
    assert hass.states.get("select.ha_iptv_region").state == "PL"
    assert hass.states.get("select.ha_iptv_group").state == "PL| NEWS"
    assert hass.states.get("select.ha_iptv_group").attributes["options"] == ["PL| NEWS", "PL| SPORT"]


async def test_player_controls(hass, api, entry):
    await _setup(hass, entry)
    await hass.services.async_call("media_player", "media_next_track", {"entity_id": PLAYER}, blocking=True)
    api.command.assert_called_with(cmd="zap", delta=1)
    await hass.services.async_call("media_player", "play_media", {"entity_id": PLAYER, "media_content_type": "channel", "media_content_id": "channel:c2"}, blocking=True)
    api.command.assert_called_with(cmd="play", channel_id="c2")
    await hass.services.async_call("media_player", "select_source", {"entity_id": PLAYER, "source": "UK"}, blocking=True)
    api.command.assert_called_with(cmd="region", region="UK")
    await hass.services.async_call("media_player", "media_pause", {"entity_id": PLAYER}, blocking=True)
    api.command.assert_called_with(cmd="pause")


async def test_browse(hass, api, entry, hass_ws_client):
    await _setup(hass, entry)
    ws = await hass_ws_client(hass)
    async def browse(cid=None):
        msg = {"id": browse.n, "type": "media_player/browse_media", "entity_id": PLAYER}
        browse.n += 1
        if cid:
            msg["media_content_id"] = cid; msg["media_content_type"] = cid.split(":")[0]
        await ws.send_json(msg)
        return (await ws.receive_json())["result"]
    browse.n = 1
    root = await browse()
    assert [c["media_content_id"] for c in root["children"]] == ["region:PL", "region:UK"]
    reg = await browse("region:PL")
    assert [c["media_content_id"] for c in reg["children"]] == ["group:g1", "group:g2"]
    grp = await browse("group:g1")
    assert [c["media_content_id"] for c in grp["children"]] == ["channel:c1", "channel:c2"]
    assert grp["children"][0]["title"].endswith("[OFF AIR]")
    assert grp["children"][1]["title"].startswith("★ PL: NEWS TWO") and "[FHD]" in grp["children"][1]["title"]


async def test_services_with_response(hass, api, entry):
    await _setup(hass, entry)
    r = await hass.services.async_call(DOMAIN, "search_channels", {"query": "newsone"}, blocking=True, return_response=True)
    assert r["channels"][0]["id"] == "c1"
    r = await hass.services.async_call(DOMAIN, "quality_report", {"group": "g1", "only_mismatches": True}, blocking=True, return_response=True)
    assert r["count"] == 1
    r = await hass.services.async_call(DOMAIN, "get_guide", {"query": "newsone"}, blocking=True, return_response=True)
    assert r["channel_id"] == "c1" and r["programmes"]
    await hass.services.async_call(DOMAIN, "play_channel", {"query": "newstwo", "group": "PL"}, blocking=True)
    api.command.assert_called_with(cmd="play", query="newstwo", group="PL")
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call(DOMAIN, "play_channel", {}, blocking=True)


async def test_watch_and_events(hass, api, entry, freezer):
    from datetime import timedelta
    from pytest_homeassistant_custom_component.common import async_capture_events, async_fire_time_changed
    import time
    await _setup(hass, entry)
    ev = async_capture_events(hass, "ha_iptv_watch_triggered")
    now = time.time()
    api.epg.return_value = {"c1": [{"title": "Premier League: Arsenal", "desc": "", "start": now + 100, "stop": now + 5000}]}
    r = await hass.services.async_call(DOMAIN, "watch_programme", {"title": "premier league", "channel_id": "c1", "lead_minutes": 5}, blocking=True, return_response=False)
    assert len(entry.runtime_data.scheduler.watches) == 1
    freezer.tick(timedelta(seconds=61))
    async_fire_time_changed(hass)
    await hass.async_block_till_done()
    assert len(ev) == 1 and ev[0].data["title"].startswith("Premier")
    api.command.assert_called_with(cmd="play", channel_id="c1")
    assert entry.runtime_data.scheduler.watches == []          # once=True removed it
    assert hass.services.has_service(DOMAIN, "list_watches")


async def test_sleep_timer(hass, api, entry, freezer):
    from datetime import timedelta
    from pytest_homeassistant_custom_component.common import async_fire_time_changed
    await _setup(hass, entry)
    await hass.services.async_call(DOMAIN, "sleep_timer", {"minutes": 10}, blocking=True)
    assert entry.runtime_data.scheduler.sleep_at
    freezer.tick(timedelta(minutes=10, seconds=1))
    async_fire_time_changed(hass)
    await hass.async_block_till_done()
    calls = [c.kwargs.get("cmd") for c in api.command.call_args_list]
    assert calls == ["stop", "home"]


async def test_offline_then_launch_via_adb(hass, api, entry):
    async def _down(wait=None, timeout=25):
        if wait is None:
            raise HaIptvConnectionError("down")
        await _hold()
    api.state.side_effect = _down
    await _setup(hass, entry)
    assert hass.states.get(PLAYER).state == "off"
    assert hass.states.get("binary_sensor.ha_iptv_app_reachable").state == "off"
    from unittest.mock import AsyncMock, patch
    from pytest_homeassistant_custom_component.common import async_mock_service
    calls = async_mock_service(hass, "androidtv", "adb_command")
    async def _up(wait=None, timeout=25):
        if wait is None:
            return dict(STATE)
        await _hold()
    api.state.side_effect = _up   # app comes up after launch
    await hass.services.async_call(DOMAIN, "play_channel", {"query": "News One"}, blocking=True)
    assert calls and "am start -n pl.home.channeldeck/.MainActivity" in calls[0].data["command"]
    assert "--es query 'News One'" in calls[0].data["command"] and "--es cmd play" in calls[0].data["command"]


async def _hold():
    import asyncio
    await asyncio.sleep(3600)


async def test_calendar(hass, api, entry):
    await _setup(hass, entry)
    from datetime import datetime, timezone
    st = hass.states.get("calendar.ha_iptv_tv_guide")
    assert st is not None


def test_intent_command():
    s = intent_command({"cmd": "play", "query": "a b'c", "delta": -1, "play": True})
    assert s.startswith("am start -n pl.home.channeldeck/.MainActivity") and "--ei delta -1" in s and "--ez play true" in s
    assert "'a b'\"'\"'c'" in s


async def test_config_flow(hass, api):
    r = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {"host": "5.6.7.8", "port": 8765, "token": "x"})
    assert r["step_id"] == "launch"
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {"adb_entity": "media_player.stick"})
    assert r["type"] == "create_entry" and r["options"]["adb_entity"] == "media_player.stick"


async def test_config_flow_errors(hass, api):
    api.state.side_effect = HaIptvAuthError("no")
    r = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {"host": "5.6.7.8", "port": 8765, "token": "x"})
    assert r["errors"] == {"base": "invalid_auth"}


async def test_push_updates_and_events(hass, api, entry):
    import asyncio
    from pytest_homeassistant_custom_component.common import async_capture_events
    changed = async_capture_events(hass, "ha_iptv_channel_changed")
    new = copy.deepcopy(STATE); new["v"] = 6
    new["channel"] = {**STATE["channel"], "id": "c2", "name": "PL: NEWS TWO"}
    new["tech"] = {**STATE["tech"], "mismatch": False}
    gate = asyncio.Event()
    async def _state(wait=None, timeout=25):
        if wait is None or wait == -1:
            return copy.deepcopy(STATE)
        if wait == 5:
            await gate.wait()
            return copy.deepcopy(new)
        await _hold()
    api.state.side_effect = _state
    await _setup(hass, entry)
    assert hass.states.get(PLAYER).attributes["media_channel"] == "PL: NEWS ONE"
    gate.set()
    await asyncio.sleep(0.05); await hass.async_block_till_done()
    assert hass.states.get(PLAYER).attributes["media_channel"] == "PL: NEWS TWO"
    assert hass.states.get("binary_sensor.ha_iptv_quality_mismatch").state == "off"
    assert len(changed) == 1 and changed[0].data["channel"] == "PL: NEWS TWO"


async def test_black_picture_flags_problem(hass, api, entry):
    import asyncio
    from pytest_homeassistant_custom_component.common import async_capture_events
    errs = async_capture_events(hass, "ha_iptv_stream_error")
    bad = copy.deepcopy(STATE); bad["v"] = 6; bad["picture"] = "black"
    gate = asyncio.Event()
    async def _state(wait=None, timeout=25):
        if wait is None or wait == -1:
            return copy.deepcopy(STATE)
        if wait == 5:
            await gate.wait()
            return copy.deepcopy(bad)
        await _hold()
    api.state.side_effect = _state
    await _setup(hass, entry)
    assert hass.states.get("binary_sensor.ha_iptv_stream_problem").state == "off"
    gate.set()
    await asyncio.sleep(0.05); await hass.async_block_till_done()
    assert hass.states.get("binary_sensor.ha_iptv_stream_problem").state == "on"
    assert errs and errs[0].data["error"] == "black_picture"


async def test_film_shows_in_media_player(hass: HomeAssistant, api, entry):
    film = {**copy.deepcopy(STATE), "power": "playing", "channel": None, "now": None, "next": None,
            "vod": {"kind": "movie", "id": "5", "title": "A Film", "ext": "mkv", "poster": "http://x/p.jpg", "series": "", "pos": 61000, "dur": 6000000}}
    async def _state(wait=None, timeout=25):
        if wait is None:
            return copy.deepcopy(film)
        await _hang()
    api.state.side_effect = _state
    await _setup(hass, entry)
    st = hass.states.get(PLAYER)
    assert st.state == MediaPlayerState.PLAYING
    assert st.attributes["media_title"] == "A Film" and st.attributes["media_content_type"] == "movie"
    assert st.attributes["media_duration"] == 6000 and st.attributes["media_position"] == 61
    assert st.attributes["entity_picture_local"] or st.attributes.get("entity_picture")


async def test_sync_services_buttons_and_sensor_exist(hass: HomeAssistant, api, entry):
    await _setup(hass, entry)
    assert hass.services.has_service(DOMAIN, "continue_on") and hass.services.has_service(DOMAIN, "sync_now")
    assert hass.states.get("button.ha_iptv_continue_here") is not None
    assert hass.states.get("sensor.ha_iptv_last_synced") is not None
    await hass.services.async_call(DOMAIN, "sync_now", {}, blocking=True)


async def test_continue_here_with_nothing_to_continue_reports_it(hass: HomeAssistant, api, entry):
    await _setup(hass, entry)
    with pytest.raises(HomeAssistantError, match="nothing to continue"):
        await hass.services.async_call("button", "press", {"entity_id": "button.ha_iptv_continue_here"}, blocking=True)


async def test_name_is_pushed_to_the_app(hass: HomeAssistant, api, entry):
    hass.config_entries.async_update_entry(entry, options={**entry.options, "device_name": "Bedroom"})
    await _setup(hass, entry)
    api.command.assert_any_call(cmd="config", name="Bedroom")
