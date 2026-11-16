"""HA IPTV: control and observe the HA IPTV Fire TV app."""
from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT, Platform
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import HaIptvApi
from .const import CONF_TOKEN, DOMAIN
from .coordinator import HaIptvCoordinator
from .scheduler import Scheduler
from .services import async_register_services
from .sync import SyncHub
from .web import async_register_views

PLATFORMS = [Platform.MEDIA_PLAYER, Platform.SENSOR, Platform.BINARY_SENSOR, Platform.SELECT, Platform.BUTTON, Platform.CALENDAR]

type HaIptvConfigEntry = ConfigEntry[HaIptvCoordinator]


async def async_setup_entry(hass: HomeAssistant, entry: HaIptvConfigEntry) -> bool:
    api = HaIptvApi(async_get_clientsession(hass), entry.data[CONF_HOST], entry.data[CONF_PORT], entry.data[CONF_TOKEN])
    coord = HaIptvCoordinator(hass, entry, api)
    await coord.async_config_entry_first_refresh()
    if coord.data.get("error") == "auth":
        raise ConfigEntryAuthFailed
    entry.runtime_data = coord
    domain_data = hass.data.setdefault(DOMAIN, {})
    if "hub" not in domain_data:
        domain_data["hub"] = SyncHub(hass)
        await domain_data["hub"].async_load()
    coord.hub = domain_data["hub"]
    coord.hub.register(coord)
    coord.scheduler = Scheduler(hass, coord)
    await coord.scheduler.async_load()
    domain_data[entry.entry_id] = coord
    async_register_services(hass)
    await async_register_views(hass)
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_reload))
    coord.start_push()
    coord.scheduler.start()
    coord.request_sync()
    hass.async_create_task(coord.async_refresh_catalog())
    return True


async def _reload(hass: HomeAssistant, entry: HaIptvConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: HaIptvConfigEntry) -> bool:
    entry.runtime_data.scheduler.stop()
    entry.runtime_data.hub.unregister(entry.runtime_data)
    if ok := await hass.config_entries.async_unload_platforms(entry, PLATFORMS):
        hass.data[DOMAIN].pop(entry.entry_id, None)
    return ok
