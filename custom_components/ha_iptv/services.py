"""Integration services."""
from __future__ import annotations

import voluptuous as vol
from homeassistant.core import HomeAssistant, ServiceCall, SupportsResponse
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv

from .api import HaIptvError
from .const import DOMAIN

ENTRY = {vol.Optional("entry_id"): cv.string}

SCHEMAS: dict[str, vol.Schema] = {
    "play_channel": vol.Schema({**ENTRY, vol.Optional("channel_id"): cv.string, vol.Optional("query"): cv.string, vol.Optional("group"): cv.string}),
    "zap": vol.Schema({**ENTRY, vol.Optional("delta", default=1): vol.All(vol.Coerce(int), vol.In([-1, 1]))}),
    "set_region": vol.Schema({**ENTRY, vol.Required("region"): cv.string, vol.Optional("play", default=False): cv.boolean}),
    "set_group": vol.Schema({**ENTRY, vol.Required("group"): cv.string, vol.Optional("play", default=False): cv.boolean}),
    "favourite": vol.Schema({**ENTRY, vol.Optional("channel_id"): cv.string, vol.Optional("on"): cv.boolean}),
    "watch_programme": vol.Schema({
        **ENTRY, vol.Required("title"): cv.string, vol.Optional("channel_id"): cv.string, vol.Optional("group"): cv.string,
        vol.Optional("action", default="tune"): vol.In(["tune", "event"]),
        vol.Optional("lead_minutes", default=0): vol.All(vol.Coerce(int), vol.Range(min=0, max=120)),
        vol.Optional("once", default=True): cv.boolean}),
    "remove_watch": vol.Schema({**ENTRY, vol.Required("watch"): cv.string}),
    "list_watches": vol.Schema({**ENTRY}),
    "sleep_timer": vol.Schema({**ENTRY, vol.Required("minutes"): vol.All(vol.Coerce(float), vol.Range(min=0, max=720)),
                               vol.Optional("turn_off_tv", default=False): cv.boolean}),
    "survey_group": vol.Schema({**ENTRY, vol.Optional("group"): cv.string, vol.Optional("seconds", default=6): vol.All(vol.Coerce(int), vol.Range(min=1, max=30))}),
    "stop_survey": vol.Schema({**ENTRY}),
    "quality_report": vol.Schema({**ENTRY, vol.Required("group"): cv.string, vol.Optional("only_mismatches", default=False): cv.boolean}),
    "search_channels": vol.Schema({**ENTRY, vol.Required("query"): cv.string, vol.Optional("limit", default=20): vol.All(vol.Coerce(int), vol.Range(min=1, max=100)),
                                   vol.Optional("group"): cv.string}),
    "get_guide": vol.Schema({**ENTRY, vol.Optional("channel_id"): cv.string, vol.Optional("query"): cv.string,
                             vol.Optional("limit", default=8): vol.All(vol.Coerce(int), vol.Range(min=1, max=30))}),
}
RESPONSES = {"list_watches", "quality_report", "search_channels", "get_guide"}


def _coord(hass: HomeAssistant, call: ServiceCall):
    entries = hass.data.get(DOMAIN, {})
    if eid := call.data.get("entry_id"):
        if eid not in entries:
            raise HomeAssistantError(f"unknown entry_id {eid}")
        return entries[eid]
    if not entries:
        raise HomeAssistantError("HA IPTV is not set up")
    return next(iter(entries.values()))


async def _handle(hass: HomeAssistant, call: ServiceCall):
    c = _coord(hass, call)
    d, name = call.data, call.service
    try:
        if name == "play_channel":
            if not (d.get("channel_id") or d.get("query")):
                raise HomeAssistantError("give channel_id or query")
            r = await c.async_command(cmd="play", **{k: d[k] for k in ("channel_id", "query", "group") if k in d})
            if (r or {}).get("ok") is False:
                raise HomeAssistantError(r.get("error", "failed"))
        elif name == "zap":
            await c.async_command(cmd="zap", delta=d["delta"])
        elif name == "set_region":
            await c.async_command(cmd="region", region=d["region"], play=d["play"])
        elif name == "set_group":
            await c.async_command(cmd="group", group=d["group"], play=d["play"])
        elif name == "favourite":
            await c.api.command(cmd="favourite", **{k: d[k] for k in ("channel_id", "on") if k in d})
            c.invalidate_channels()
        elif name == "watch_programme":
            w = c.scheduler.add_watch(d["title"], channel_id=d.get("channel_id"), group=d.get("group"),
                                      action=d["action"], lead=d["lead_minutes"], once=d["once"])
            return {"watch": w} if name in RESPONSES else None
        elif name == "remove_watch":
            if not c.scheduler.remove_watch(d["watch"]):
                raise HomeAssistantError("no such watch")
        elif name == "list_watches":
            return {"watches": c.scheduler.watches}
        elif name == "sleep_timer":
            c.scheduler.set_sleep(d["minutes"], power_off_tv=d["turn_off_tv"])
        elif name == "survey_group":
            await c.async_command(cmd="survey", group=d.get("group", ""), seconds=d["seconds"])
        elif name == "stop_survey":
            await c.api.command(cmd="survey_stop")
        elif name == "quality_report":
            rows = await c.api.quality(d["group"])
            if d["only_mismatches"]:
                rows = [r for r in rows if r["verdict"] == "mismatch"]
            return {"channels": rows, "count": len(rows)}
        elif name == "search_channels":
            return {"channels": await c.api.search(d["query"], d["limit"], d.get("group"))}
        elif name == "get_guide":
            cid = d.get("channel_id")
            if not cid:
                hits = await c.api.search(d.get("query", ""), 1)
                if not hits:
                    raise HomeAssistantError("no matching channel")
                cid = hits[0]["id"]
            return {"channel_id": cid, "programmes": (await c.api.epg([cid], d["limit"])).get(cid, [])}
    except HaIptvError as err:
        raise HomeAssistantError(str(err)) from err
    return None


def async_register_services(hass: HomeAssistant) -> None:
    if hass.services.has_service(DOMAIN, "play_channel"):
        return
    for name, schema in SCHEMAS.items():
        async def handler(call: ServiceCall, _n=name):
            return await _handle(hass, call)
        hass.services.async_register(
            DOMAIN, name, handler, schema=schema,
            supports_response=SupportsResponse.ONLY if name in RESPONSES else SupportsResponse.NONE)
