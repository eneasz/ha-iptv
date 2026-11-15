"""Config flow for HA IPTV."""
from __future__ import annotations

import asyncio
import hashlib
import secrets
from typing import Any

import voluptuous as vol
from homeassistant.components import persistent_notification
from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import callback
from homeassistant.helpers import selector
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .api import HaIptvApi, HaIptvAuthError, HaIptvError
from .const import CONF_ADB_ENTITY, CONF_DEVICE_NAME, CONF_TOKEN, CONF_TURN_OFF_TV, CONF_TV_ENTITY, DEFAULT_PORT, DOMAIN

CONF_SKIP_COPY = "skip_copy"
PAIR_WAIT = 125  # seconds; the stick gives up after 120


def pair_code(ha_nonce: str, stick_nonce: str) -> str:
    """The 4 digits both screens show (same rule as the app's Pairing.codeOf)."""
    return "%04d" % (int.from_bytes(hashlib.sha256((ha_nonce + stick_nonce).encode()).digest()[:4], "big") % 10000)
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
                self._data = {**self._data, CONF_TOKEN: res["token"]}
                return "approved"
            if res.get("status") in ("denied", "expired", "unknown"):
                return res["status"]
            await asyncio.sleep(1)
        return "expired"

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Ask the stick to show Approve/Deny; the same 4-digit code appears here and on the TV."""
        if not self._pair:
            nonce = secrets.token_hex(8)
            try:
                res = await self._api().pair_request(nonce)
            except HaIptvError:
                return self.async_abort(reason="cannot_connect")
            if not res.get("ok"):
                return self.async_abort(reason="pair_busy" if res.get("error") == "busy" else "pair_closed")
            self._pair = {"id": res["id"], "nonce": nonce, "code": pair_code(nonce, res["snonce"])}
            self._name = self._name or str(res.get("name") or "")
        if self._task is None:
            self._task = self.hass.async_create_task(self._wait_for_approval())
        if not self._task.done():
            return self.async_show_progress(step_id="pair", progress_action="approve", progress_task=self._task,
                                            description_placeholders={"code": self._pair["code"], "name": self._name or self._data[CONF_HOST]})
        status = self._task.result()
        self._task = None
        if status != "approved":
            self._reason = "pair_denied" if status == "denied" else "pair_expired"
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
            if source is not None and not skip:
                try:
                    login = await source.api.provision()
                    if not login.get("server"):
                        raise HaIptvError("the other TV's app is too old to share its login")
                    await self._api(self._data[CONF_TOKEN]).provision_set(login["server"], login.get("user", ""), login.get("pass", ""))
                except HaIptvError as err:
                    # the approved token must never be lost: the TV is added anyway and the user is told what to do
                    persistent_notification.async_create(
                        self.hass, f"{title} was added, but its provider login could not be copied from {source.device_name or source.entry.title}: {err}. "
                        "Turn the other TV on and re-add this one, or type the login on the TV.", title="HA IPTV", notification_id=f"ha_iptv_copy_{self.unique_id}")
            return self.async_create_entry(title=title, data=self._data, options=user_input)
        schema = _extras({CONF_DEVICE_NAME: self._name})
        if source is not None:
            schema = schema.extend({vol.Optional(CONF_SKIP_COPY, default=False): bool})
        return self.async_show_form(step_id="launch", data_schema=schema,
                                    description_placeholders={"source": (source.device_name or source.entry.title) if source else ""})

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
        if user_input is not None:
            if (name := (user_input.get(CONF_DEVICE_NAME) or "").strip()) and name != self.config_entry.title:
                self.hass.config_entries.async_update_entry(self.config_entry, title=name)
            return self.async_create_entry(data=user_input)
        return self.async_show_form(step_id="init", data_schema=_extras(dict(self.config_entry.options)))
