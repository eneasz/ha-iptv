"""Config flow for HA IPTV."""
from __future__ import annotations

from typing import Any

import voluptuous as vol
from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import callback
from homeassistant.helpers import selector
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import HaIptvApi, HaIptvAuthError, HaIptvError
from .const import CONF_ADB_ENTITY, CONF_TOKEN, CONF_TURN_OFF_TV, CONF_TV_ENTITY, DEFAULT_PORT, DOMAIN

_MP = selector.EntitySelector(selector.EntitySelectorConfig(domain="media_player"))


def _extras(defaults: dict) -> vol.Schema:
    def opt(key: str):
        v = defaults.get(key)
        return vol.Optional(key, description={"suggested_value": v}) if v else vol.Optional(key)
    return vol.Schema({
        opt(CONF_ADB_ENTITY): _MP,
        opt(CONF_TV_ENTITY): _MP,
        vol.Optional(CONF_TURN_OFF_TV, default=defaults.get(CONF_TURN_OFF_TV, False)): bool,
    })


class HaIptvConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    def __init__(self) -> None:
        self._data: dict[str, Any] = {}

    async def _check(self, data: dict) -> str | None:
        api = HaIptvApi(async_get_clientsession(self.hass), data[CONF_HOST], data[CONF_PORT], data[CONF_TOKEN])
        try:
            await api.state()
        except HaIptvAuthError:
            return "invalid_auth"
        except HaIptvError:
            return "cannot_connect"
        return None

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            await self.async_set_unique_id(f"{user_input[CONF_HOST]}:{user_input[CONF_PORT]}")
            self._abort_if_unique_id_configured()
            if err := await self._check(user_input):
                errors["base"] = err
            else:
                self._data = user_input
                return await self.async_step_launch()
        schema = vol.Schema({
            vol.Required(CONF_HOST): str,
            vol.Required(CONF_PORT, default=DEFAULT_PORT): int,
            vol.Required(CONF_TOKEN): str,
        })
        return self.async_show_form(step_id="user", data_schema=self.add_suggested_values_to_schema(schema, user_input), errors=errors)

    async def async_step_launch(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(title=f"HA IPTV ({self._data[CONF_HOST]})", data=self._data, options=user_input)
        return self.async_show_form(step_id="launch", data_schema=_extras({}))

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
            return self.async_create_entry(data=user_input)
        return self.async_show_form(step_id="init", data_schema=_extras(dict(self.config_entry.options)))
