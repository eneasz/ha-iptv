"""Config flow for HA IPTV."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import secrets
from typing import Any

import voluptuous as vol
from homeassistant.components import persistent_notification
from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import callback
from homeassistant.helpers import selector
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .api import HaIptvApi, HaIptvAuthError, HaIptvError
from .const import CONF_ADB_ENTITY, CONF_DEVICE_NAME, CONF_TOKEN, CONF_TURN_OFF_TV, CONF_TV_ENTITY, DEFAULT_PORT, DOMAIN

CONF_SKIP_COPY = "skip_copy"
CONF_PROV_SERVER, CONF_PROV_USER, CONF_PROV_PASS = "provider_server", "provider_username", "provider_password"
PAIR_WAIT = 125  # seconds; the stick gives up after 120


def pair_code(ha_nonce: str, ha_pub: str, stick_nonce: str, stick_pub: str) -> str:
    """The 4 digits both screens show (same rule as the app's Pairing.codeOf). It covers both public keys, so a man in the middle
    (who has to run two different key exchanges) makes the screens disagree."""
    return "%04d" % (int.from_bytes(hashlib.sha256((ha_nonce + ha_pub + stick_nonce + stick_pub).encode()).digest()[:4], "big") % 10000)


def pair_keypair() -> tuple[ec.EllipticCurvePrivateKey, str]:
    """A throw-away P-256 key for one pairing; returns it with its public point (uncompressed, base64)."""
    key = ec.generate_private_key(ec.SECP256R1())
    raw = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    return key, base64.b64encode(raw).decode()


def pair_decrypt(key: ec.EllipticCurvePrivateKey, ha_pub: str, stick_pub: str, pair_id: str, iv: str, ct: str) -> str:
    """Open the token the stick sealed with AES-GCM (key = SHA-256 of label, ECDH secret, both public points)."""
    peer = base64.b64decode(stick_pub)
    secret = key.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer))
    k = hashlib.sha256(b"haiptv-pair-v1" + secret + base64.b64decode(ha_pub) + peer).digest()
    return AESGCM(k).decrypt(base64.b64decode(iv), base64.b64decode(ct), pair_id.encode()).decode()
_MP = selector.EntitySelector(selector.EntitySelectorConfig(domain="media_player"))


def _extras(defaults: dict) -> vol.Schema:
    def opt(key: str):
        v = defaults.get(key)
        return vol.Optional(key, description={"suggested_value": v}) if v else vol.Optional(key)
    return vol.Schema({
        opt(CONF_DEVICE_NAME): str,
        opt(CONF_ADB_ENTITY): _MP,
        opt(CONF_TV_ENTITY): _MP,
        vol.Optional(CONF_TURN_OFF_TV, default=defaults.get(CONF_TURN_OFF_TV, False)): bool,
    })


class HaIptvConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    def __init__(self) -> None:
        self._data: dict[str, Any] = {}
        self._state: dict[str, Any] = {}
        self._name = ""
        self._task: asyncio.Task | None = None
        self._pair: dict[str, str] = {}
        self._reason = "pair_failed"
        self._title = ""
        self._options: dict[str, Any] = {}

    async def _check(self, data: dict) -> str | None:
        api = HaIptvApi(async_get_clientsession(self.hass), data[CONF_HOST], data[CONF_PORT], data[CONF_TOKEN])
        try:
            self._state = await api.state()
        except HaIptvAuthError:
            return "invalid_auth"
        except HaIptvError:
            return "cannot_connect"
        self._name = self._name or str(self._state.get("name") or "")
        return None

    def _login_source(self):
        """A set-up stick already in Home Assistant whose provider login can be copied to this one."""
        for key, coord in self.hass.data.get(DOMAIN, {}).items():
            if key != "hub" and getattr(coord, "data", None) and coord.data.get("configured") is True:   # only app 0.6+ reports this and can hand out its login
                return coord
        return None

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            await self.async_set_unique_id(f"{user_input[CONF_HOST]}:{user_input[CONF_PORT]}")
            self._abort_if_unique_id_configured()
            if not user_input.get(CONF_TOKEN, "").strip():       # no token: pair by approving on the TV
                self._data = {CONF_HOST: user_input[CONF_HOST], CONF_PORT: user_input[CONF_PORT]}
                return await self.async_step_pair()
            if err := await self._check(user_input):
                errors["base"] = err
            else:
                self._data = user_input
                return await self.async_step_launch()
        schema = vol.Schema({
            vol.Required(CONF_HOST): str,
            vol.Required(CONF_PORT, default=DEFAULT_PORT): int,
            vol.Optional(CONF_TOKEN, default=""): str,
        })
        return self.async_show_form(step_id="user", data_schema=self.add_suggested_values_to_schema(schema, user_input), errors=errors)

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        """A stick announced itself: only the code shown on its screen is needed."""
        host, port = discovery_info.host, discovery_info.port or DEFAULT_PORT
        await self.async_set_unique_id(f"{host}:{port}")
        self._abort_if_unique_id_configured()
        self._data = {CONF_HOST: host, CONF_PORT: port}
        self._name = str(discovery_info.properties.get("name") or "")
        self.context["title_placeholders"] = {"name": self._name or host}
        return await self.async_step_pair()

    def _api(self, token: str = "") -> HaIptvApi:
        return HaIptvApi(async_get_clientsession(self.hass), self._data[CONF_HOST], self._data[CONF_PORT], token)

    async def _wait_for_approval(self) -> str:
        """Poll the stick until Approve/Deny/timeout; returns the status and stores the token."""
        api = self._api()
        loop = asyncio.get_running_loop()
        end = loop.time() + PAIR_WAIT
        while loop.time() < end:
            try:
                res = await api.pair_poll(self._pair["id"], self._pair["nonce"])
            except HaIptvError:
                res = {"status": "pending"}
            if res.get("status") == "approved":
                try:
                    token = pair_decrypt(self._pair["key"], self._pair["pub"], self._pair["spub"], self._pair["id"], res["iv"], res["ct"])
                except (KeyError, ValueError, InvalidTag):
                    return "unknown"
                self._data = {**self._data, CONF_TOKEN: token}
                return "approved"
            if res.get("status") in ("denied", "expired", "unknown"):
                return res["status"]
            await asyncio.sleep(1)
        return "expired"

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Ask the stick to show Approve/Deny; the same 4-digit code appears here and on the TV."""
        if not self._pair:
            nonce = secrets.token_hex(8)
            key, pub = pair_keypair()
            try:
                res = await self._api().pair_request(nonce, pub)
            except HaIptvError:
                return self.async_abort(reason="cannot_connect")
            if not res.get("ok"):
                return self.async_abort(reason="pair_busy" if res.get("error") == "busy" else "pair_closed")
            if not res.get("spub"):
                return self.async_abort(reason="pair_outdated")      # an app without encryption: never accept a token in the clear
            self._pair = {"id": res["id"], "nonce": nonce, "key": key, "pub": pub, "spub": res["spub"],
                          "code": pair_code(nonce, pub, res["snonce"], res["spub"])}
            self._name = self._name or str(res.get("name") or "")
        if self._task is None:
            self._task = self.hass.async_create_task(self._wait_for_approval())
        if not self._task.done():
            return self.async_show_progress(step_id="pair", progress_action="approve", progress_task=self._task,
                                            description_placeholders={"code": self._pair["code"], "name": self._name or self._data[CONF_HOST]})
        status = self._task.result()
        self._task = None
        if status != "approved":
            self._reason = {"denied": "pair_denied", "unknown": "pair_failed_secure"}.get(status, "pair_expired")
            return self.async_show_progress_done(next_step_id="pair_failed")
        return self.async_show_progress_done(next_step_id="pair_done")

    async def async_step_pair_failed(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        return self.async_abort(reason=self._reason)

    async def async_step_pair_done(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if err := await self._check(self._data):
            return self.async_abort(reason=err)
        return await self.async_step_launch()

    async def async_step_launch(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Name + options. If another TV is set up, its provider login is copied automatically (opt-out)."""
        source = self._login_source() if self._state.get("configured", True) is False else None
        if user_input is not None:
            user_input = dict(user_input)
            skip = user_input.pop(CONF_SKIP_COPY, False)
            title = (user_input.get(CONF_DEVICE_NAME) or "").strip() or self._name or f"HA IPTV ({self._data[CONF_HOST]})"
            copied = False
            if source is not None and not skip:
                try:
                    login = await source.api.provision()
                    if not login.get("server"):
                        raise HaIptvError("the other TV's app is too old to share its login")
                    await self._api(self._data[CONF_TOKEN]).provision_set(login["server"], login.get("user", ""), login.get("pass", ""))
                    copied = True
                except HaIptvError as err:
                    # the approved token must never be lost: the TV is added anyway and the user is told what to do
                    persistent_notification.async_create(
                        self.hass, f"{title} was added, but its provider login could not be copied from {source.device_name or source.entry.title}: {err}. "
                        "Turn the other TV on and re-add this one, or type the login on the TV.", title="HA IPTV", notification_id=f"ha_iptv_copy_{self.unique_id}")
            self._title, self._options = title, user_input
            if self._state.get("configured", True) is False and not copied:
                return await self.async_step_login()           # nobody could copy it: ask for it here, with a real keyboard
            return self.async_create_entry(title=title, data=self._data, options=user_input)
        schema = _extras({CONF_DEVICE_NAME: self._name})
        if source is not None:
            schema = schema.extend({vol.Optional(CONF_SKIP_COPY, default=False): bool})
        return self.async_show_form(step_id="launch", data_schema=schema,
                                    description_placeholders={"source": (source.device_name or source.entry.title) if source else ""})

    async def async_step_login(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """The TV has no provider login yet and none could be copied: type it here instead of on the TV remote."""
        errors: dict[str, str] = {}
        if user_input is not None:
            server, user, pw = (user_input.get(k, "").strip() for k in (CONF_PROV_SERVER, CONF_PROV_USER, CONF_PROV_PASS))
            if not (server or user or pw):
                return self.async_create_entry(title=self._title, data=self._data, options=self._options)   # skipped
            try:
                await self._api(self._data[CONF_TOKEN]).provision_set(server, user, pw)
            except HaIptvError:
                errors["base"] = "login_failed"
            else:
                return self.async_create_entry(title=self._title, data=self._data, options=self._options)
        schema = vol.Schema({vol.Optional(CONF_PROV_SERVER): str, vol.Optional(CONF_PROV_USER): str,
                             vol.Optional(CONF_PROV_PASS): selector.TextSelector(selector.TextSelectorConfig(type=selector.TextSelectorType.PASSWORD))})
        return self.async_show_form(step_id="login", data_schema=self.add_suggested_values_to_schema(schema, user_input), errors=errors)

    async def async_step_reauth(self, entry_data: dict[str, Any]) -> ConfigFlowResult:
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        entry = self._get_reauth_entry()
        if user_input is not None:
            data = {**entry.data, CONF_TOKEN: user_input[CONF_TOKEN]}
            if err := await self._check(data):
                errors["base"] = err
            else:
                return self.async_update_reload_and_abort(entry, data=data)
        return self.async_show_form(step_id="reauth_confirm", data_schema=vol.Schema({vol.Required(CONF_TOKEN): str}), errors=errors)

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return HaIptvOptionsFlow()


class HaIptvOptionsFlow(OptionsFlow):
    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            user_input = dict(user_input)
            server, user, pw = (str(user_input.pop(k, "") or "").strip() for k in (CONF_PROV_SERVER, CONF_PROV_USER, CONF_PROV_PASS))
            if server or user or pw:                                 # send a provider login to this TV (never stored in Home Assistant)
                coord = self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)
                try:
                    if coord is None:
                        raise HaIptvError("not loaded")
                    await coord.api.provision_set(server, user, pw)
                except HaIptvError:
                    errors["base"] = "login_failed"
            if not errors:
                if (name := (user_input.get(CONF_DEVICE_NAME) or "").strip()) and name != self.config_entry.title:
                    self.hass.config_entries.async_update_entry(self.config_entry, title=name)
                return self.async_create_entry(data=user_input)
        schema = _extras(dict(self.config_entry.options)).extend({
            vol.Optional(CONF_PROV_SERVER): str, vol.Optional(CONF_PROV_USER): str,
            vol.Optional(CONF_PROV_PASS): selector.TextSelector(selector.TextSelectorConfig(type=selector.TextSelectorType.PASSWORD))})
        return self.async_show_form(step_id="init", data_schema=schema, errors=errors)
