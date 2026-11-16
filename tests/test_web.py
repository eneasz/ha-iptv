"""Stream proxy: playlist rewriting and the opaque token store."""
from __future__ import annotations

from custom_components.ha_iptv import web
from custom_components.ha_iptv.web import TokenStore, rewrite_playlist

PL = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-KEY:METHOD=AES-128,URI="/key/abc"
#EXTINF:10.8,
/hls/aaa/1_928.ts
#EXTINF:9.1,
seg2.ts
"""


def test_rewrite_hides_upstream_and_resolves_relative():
    t = TokenStore()
    out = rewrite_playlist(PL, "http://tok.example:80/live/play/XYZ", t.register)
    assert "tok.example" not in out and ".ts" not in out
    seg = [l for l in out.splitlines() if l.startswith(web.SEG_PATH)]
    assert len(seg) == 2
    urls = [t.resolve(l.rsplit("/", 1)[1]) for l in seg]
    assert urls == ["http://tok.example:80/hls/aaa/1_928.ts", "http://tok.example:80/live/play/seg2.ts"]
    key = [l for l in out.splitlines() if "KEY" in l][0]
    assert t.resolve(key.split(web.SEG_PATH)[1].rstrip('"')) == "http://tok.example:80/key/abc"
    assert "#EXTINF:10.8," in out


def test_unknown_and_expired_tokens(monkeypatch):
    t = TokenStore()
    assert t.resolve("nope") is None
    tok = t.register("http://x/y")
    now = web.time.monotonic()
    monkeypatch.setattr(web.time, "monotonic", lambda: now + web.TOKEN_TTL + 1)
    assert t.resolve(tok) is None


def test_store_is_bounded(monkeypatch):
    monkeypatch.setattr(web, "MAX_TOKENS", 5)
    t = TokenStore()
    for i in range(20):
        t.register(f"http://x/{i}")
    assert len(t._m) <= 5


class _Offline:
    groups: list = []
    regions: list = []
    connected = False

    class api:
        @staticmethod
        async def provision():
            raise OSError("app not running")

    async def async_refresh_catalog(self):
        return None


async def test_offline_sticks_fall_back_to_cached_catalogue(hass):
    import json as _json

    from custom_components.ha_iptv.const import DOMAIN
    from custom_components.ha_iptv.web import Cache, GroupsView, _Provider

    cache = Cache(hass)
    await cache.load()
    cache.data.update(groups=[{"id": "g1", "name": "News", "count": 3}], regions=[], login={"server": "http://p", "user": "u", "pass": "x"})
    hass.data[DOMAIN] = {"e1": _Offline()}
    resp = await GroupsView(hass, cache).get(None)
    body = _json.loads(resp.body)
    assert body["groups"][0]["name"] == "News" and body["cached"] is True
    prov = _Provider(hass, cache)
    assert (await prov.login())["user"] == "u"
    prov.forget()
    assert "login" not in cache.data
    cache.data.pop("groups")
    resp = await GroupsView(hass, cache).get(None)
    assert resp.status == 503
