"""Authenticated HTTP views that let a browser (HTTPS Home Assistant) play the provider's live streams.

The provider only serves plain http, which browsers refuse to load from an https page, and handing the
browser the provider URL would also leak the account credentials. So the browser only ever talks to these
views (Bearer-authenticated by HA); the provider URL, user and password stay on the HA host.

* ``GET /api/ha_iptv/stream/{channel_id}.m3u8`` fetches the provider playlist and rewrites every URI in it to
  ``/api/ha_iptv/seg/{token}``.
* ``GET /api/ha_iptv/seg/{token}`` streams the upstream segment/playlist registered under that token.

Tokens are opaque, random and short lived, and the view only fetches URLs it generated itself, so a caller
can never make HA request an arbitrary address.
"""
from __future__ import annotations

import asyncio
import re
import secrets
import time
from typing import Any
from urllib.parse import urljoin

import aiohttp
from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import DOMAIN

TOKEN_TTL = 600  # seconds a rewritten URL stays valid
MAX_TOKENS = 4000
URI_ATTR = re.compile(r'URI="([^"]+)"')
SEG_PATH = "/api/ha_iptv/seg/"
CHANNEL_ID = re.compile(r"^[0-9]{1,12}$")
UPSTREAM_HEADERS = {"User-Agent": "Mozilla/5.0 (HA IPTV proxy)"}


def rewrite_playlist(text: str, base_url: str, register) -> str:
    """Rewrite all URIs of an HLS playlist to proxy URLs. `register(abs_url) -> token`."""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            out.append(line)
        elif s.startswith("#"):
            out.append(URI_ATTR.sub(lambda m: f'URI="{SEG_PATH}{register(urljoin(base_url, m.group(1)))}"', line))
        else:
            out.append(SEG_PATH + register(urljoin(base_url, s)))
    return "\n".join(out) + "\n"


class TokenStore:
    """Opaque, expiring map token -> upstream URL."""

    def __init__(self) -> None:
        self._m: dict[str, tuple[float, str]] = {}

    def register(self, url: str) -> str:
        now = time.monotonic()
        if len(self._m) >= MAX_TOKENS:
            self._m = {k: v for k, v in self._m.items() if v[0] > now}
            while len(self._m) >= MAX_TOKENS:  # still full: drop the oldest
                self._m.pop(next(iter(self._m)))
        tok = secrets.token_urlsafe(12)
        self._m[tok] = (now + TOKEN_TTL, url)
        return tok

    def resolve(self, tok: str) -> str | None:
        v = self._m.get(tok)
        if not v or v[0] < time.monotonic():
            self._m.pop(tok, None)
            return None
        return v[1]


class _Provider:
    """Provider login, fetched once from a stick through its provisioning endpoint."""

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass
        self._login: dict[str, str] | None = None
        self._lock = asyncio.Lock()

    async def login(self) -> dict[str, str] | None:
        async with self._lock:
            if self._login:
                return self._login
            for coord in [c for k, c in self._hass.data.get(DOMAIN, {}).items() if hasattr(c, "api")]:
                try:
                    got = await coord.api.provision()
                except Exception:  # stick offline, try the next one
                    continue
                if got.get("server") and got.get("user"):
                    self._login = {"server": got["server"].rstrip("/"), "user": got["user"], "pass": got.get("pass", "")}
                    return self._login
            return None

    def forget(self) -> None:
        self._login = None


class StreamView(HomeAssistantView):
    url = "/api/ha_iptv/stream/{channel}.m3u8"
    name = "api:ha_iptv:stream"
    requires_auth = True

    def __init__(self, hass: HomeAssistant, tokens: TokenStore, provider: _Provider) -> None:
        self._hass, self._tokens, self._provider = hass, tokens, provider

    async def get(self, request: web.Request, channel: str) -> web.StreamResponse:
        if not CHANNEL_ID.match(channel):
            return web.Response(status=400, text="bad channel id")
        login = await self._provider.login()
        if not login:
            return web.Response(status=503, text="no provider login available (is a stick online?)")
        url = f"{login['server']}/live/{login['user']}/{login['pass']}/{channel}.m3u8"
        try:
            async with async_get_clientsession(self._hass).get(
                url, headers=UPSTREAM_HEADERS, timeout=aiohttp.ClientTimeout(total=15)
            ) as r:
                if r.status in (401, 403):
                    self._provider.forget()
                if r.status != 200:
                    return web.Response(status=502, text=f"provider answered {r.status}")
                body = await r.text()
                base = str(r.url)
        except (aiohttp.ClientError, asyncio.TimeoutError) as err:
            return web.Response(status=502, text=type(err).__name__)
        return web.Response(
            text=rewrite_playlist(body, base, self._tokens.register),
            content_type="application/vnd.apple.mpegurl",
            headers={"Cache-Control": "no-store"},
        )


class SegmentView(HomeAssistantView):
    url = SEG_PATH + "{token}"
    name = "api:ha_iptv:seg"
    requires_auth = True

    def __init__(self, hass: HomeAssistant, tokens: TokenStore) -> None:
        self._hass, self._tokens = hass, tokens

    async def get(self, request: web.Request, token: str) -> web.StreamResponse:
        upstream = self._tokens.resolve(token)
        if not upstream:
            return web.Response(status=404, text="expired")
        sess = async_get_clientsession(self._hass)
        try:
            async with sess.get(upstream, headers=UPSTREAM_HEADERS, timeout=aiohttp.ClientTimeout(total=30, sock_read=20)) as r:
                if r.status != 200:
                    return web.Response(status=502, text=f"provider answered {r.status}")
                ctype = r.headers.get("Content-Type", "application/octet-stream")
                if "mpegurl" in ctype.lower() or upstream.split("?")[0].endswith(".m3u8"):  # nested playlist
                    body = await r.text()
                    return web.Response(
                        text=rewrite_playlist(body, str(r.url), self._tokens.register),
                        content_type="application/vnd.apple.mpegurl",
                        headers={"Cache-Control": "no-store"},
                    )
                resp = web.StreamResponse(status=200, headers={"Content-Type": ctype, "Cache-Control": "no-store"})
                await resp.prepare(request)
                async for chunk in r.content.iter_chunked(64 * 1024):
                    await resp.write(chunk)
                await resp.write_eof()
                return resp
        except (aiohttp.ClientError, asyncio.TimeoutError) as err:
            return web.Response(status=502, text=type(err).__name__)


def _coord(hass: HomeAssistant):
    """First configured stick (the catalogue is the same on all of them)."""
    for c in hass.data.get(DOMAIN, {}).values():
        if hasattr(c, "api") and hasattr(c, "groups"):
            return c
    return None


class GroupsView(HomeAssistantView):
    url = "/api/ha_iptv/groups"
    name = "api:ha_iptv:groups"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass

    async def get(self, request: web.Request) -> web.Response:
        c = _coord(self._hass)
        if c is None:
            return self.json_message("no stick configured", 503)
        if not c.groups:
            await c.async_refresh_catalog()
        return self.json({"groups": c.groups, "regions": c.regions})


class ChannelsView(HomeAssistantView):
    url = "/api/ha_iptv/channels/{group}"
    name = "api:ha_iptv:channels"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass

    async def get(self, request: web.Request, group: str) -> web.Response:
        c = _coord(self._hass)
        if c is None:
            return self.json_message("no stick configured", 503)
        try:
            return self.json(await c.async_channels(group))
        except Exception as err:  # stick offline
            return self.json_message(type(err).__name__, 502)


async def _async_register_panel(hass: HomeAssistant) -> None:
    """Static files + the full-screen player panel (/iptv-player) + the launcher card resource."""
    from pathlib import Path

    from homeassistant.components.http import StaticPathConfig

    ver = "0"
    try:
        import json as _json
        ver = _json.loads((Path(__file__).parent / "manifest.json").read_text())["version"]
    except Exception:
        pass
    await hass.http.async_register_static_paths([StaticPathConfig("/ha_iptv_static", str(Path(__file__).parent / "www"), False)])
    if "panel_custom" in hass.config.components or "frontend" in hass.config.components:
        from homeassistant.components import frontend, panel_custom

        await panel_custom.async_register_panel(
            hass,
            webcomponent_name="ha-iptv-player",
            frontend_url_path="iptv-player",
            module_url=f"/ha_iptv_static/iptv-player.js?v={ver}",
            sidebar_title="IPTV",
            sidebar_icon="mdi:television-play",
            require_admin=False,
            config={},
        )
        frontend.add_extra_js_url(hass, f"/ha_iptv_static/iptv-card.js?v={ver}")


async def async_register_views(hass: HomeAssistant) -> None:
    """Register the proxy views once."""
    data: dict[str, Any] = hass.data.setdefault(DOMAIN, {})
    if data.get("_views"):
        return
    data["_views"] = True
    tokens, provider = TokenStore(), _Provider(hass)
    hass.http.register_view(StreamView(hass, tokens, provider))
    hass.http.register_view(SegmentView(hass, tokens))
    hass.http.register_view(GroupsView(hass))
    hass.http.register_view(ChannelsView(hass))
    try:
        await _async_register_panel(hass)
    except Exception:  # never let the player panel stop the integration from loading
        import logging

        logging.getLogger(__name__).exception("could not register the IPTV player panel")
