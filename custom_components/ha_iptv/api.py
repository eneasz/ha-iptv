"""Client for the HA IPTV app's local JSON API."""
from __future__ import annotations

import asyncio
from typing import Any

import aiohttp


class HaIptvError(Exception):
    """Base error."""


class HaIptvAuthError(HaIptvError):
    """Token rejected."""


class HaIptvConnectionError(HaIptvError):
    """App not reachable (not running, TV off, network)."""


class HaIptvApi:
    """Thin async wrapper; every call raises one of the errors above."""

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int, token: str) -> None:
        self._s = session
        self._base = f"http://{host}:{port}"
        self._h = {"Authorization": f"Bearer {token}"}

    async def _req(self, method: str, path: str, *, params: dict | None = None, json: Any = None, timeout: float = 10) -> Any:
        try:
            async with self._s.request(
                method, self._base + path, headers=self._h, params=params, json=json,
                timeout=aiohttp.ClientTimeout(total=timeout),
            ) as r:
                if r.status == 401:
                    raise HaIptvAuthError("token rejected")
                if r.status != 200:
                    raise HaIptvError(f"HTTP {r.status}")
                return await r.json(content_type=None)
        except HaIptvError:
            raise
        except (aiohttp.ClientError, asyncio.TimeoutError, OSError) as err:
            raise HaIptvConnectionError(str(err) or type(err).__name__) from err

    async def state(self, wait: int | None = None, timeout: int = 25) -> dict:
        """Current state; with `wait` the call blocks until the state version changes (long poll)."""
        params = None if wait is None else {"wait": wait, "timeout": timeout}
        return await self._req("GET", "/api/state", params=params, timeout=(timeout + 10) if wait is not None else 10)

    async def groups(self) -> list[dict]:
        return await self._req("GET", "/api/groups", timeout=20)

    async def regions(self) -> list[dict]:
        return await self._req("GET", "/api/regions", timeout=20)

    async def channels(self, group: str) -> list[dict]:
        return await self._req("GET", "/api/channels", params={"group": group}, timeout=30)

    async def search(self, query: str, limit: int = 20, group: str | None = None) -> list[dict]:
        params: dict[str, Any] = {"q": query, "limit": limit}
        if group:
            params["group"] = group
        return await self._req("GET", "/api/search", params=params)

    async def epg(self, ids: list[str], limit: int = 12) -> dict[str, list[dict]]:
        return await self._req("GET", "/api/epg", params={"ids": ",".join(ids), "limit": limit}, timeout=60)

    async def quality(self, group: str) -> list[dict]:
        return await self._req("GET", "/api/quality", params={"group": group}, timeout=30)

    async def sync(self, since: int = 0) -> dict:
        """Entries that changed on the stick after sequence number `since`, plus its device id, name and epoch."""
        return await self._req("GET", "/api/sync", params={"since": since}, timeout=20)

    async def sync_push(self, entries: dict) -> dict:
        return await self._req("POST", "/api/sync", json={"entries": entries}, timeout=30)

    async def pair_request(self, nonce: str, pub: str) -> dict:
        """Ask the stick to show an Approve/Deny prompt. Needs no token; answers {ok, id, snonce, name} or {ok: false, error}."""
        return await self._req("POST", "/api/pair/request", json={"nonce": nonce, "pub": pub})

    async def pair_poll(self, pair_id: str, nonce: str) -> dict:
        """{status: pending|approved|denied|expired|unknown, token?}"""
        return await self._req("GET", "/api/pair/poll", params={"id": pair_id, "nonce": nonce})

    async def provision(self) -> dict:
        """The provider login stored on the stick (`configured` is False on a stick that is not set up)."""
        return await self._req("GET", "/api/provision")

    async def provision_set(self, server: str, user: str, password: str) -> dict:
        res = await self._req("POST", "/api/provision", json={"server": server, "user": user, "pass": password}, timeout=20)
        if isinstance(res, dict) and res.get("ok") is False:
            raise HaIptvError(res.get("error", "provisioning failed"))
        return res

    async def command(self, **cmd: Any) -> dict:
        res = await self._req("POST", "/api/command", json=cmd, timeout=20)
        if isinstance(res, dict) and res.get("ok") is False:
            raise HaIptvError(res.get("error", "command failed"))
        return res
