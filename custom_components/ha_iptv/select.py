"""Region and group selectors (browse position, not what is playing)."""
from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import HaIptvEntity


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    c = entry.runtime_data
    async_add_entities([RegionSelect(c), GroupSelect(c)])


class RegionSelect(HaIptvEntity, SelectEntity):
    _attr_translation_key = "region"
    _attr_icon = "mdi:earth"

    def __init__(self, c) -> None:
        super().__init__(c, "region_select")

    @property
    def options(self) -> list[str]:
        return [r["name"] for r in self.coordinator.regions]

    @property
    def current_option(self) -> str | None:
        r = self.d.get("region")
        return r if r in self.options else None

    async def async_select_option(self, option: str) -> None:
        await self.coordinator.async_command(cmd="region", region=option, play=True)


class GroupSelect(HaIptvEntity, SelectEntity):
    _attr_translation_key = "group"
    _attr_icon = "mdi:folder-play"

    def __init__(self, c) -> None:
        super().__init__(c, "group_select")

    def _in_region(self) -> list[dict]:
        r = self.d.get("region")
        return [g for g in self.coordinator.groups if g.get("region") == r]

    @property
    def options(self) -> list[str]:
        return [g["name"] for g in self._in_region()]

    @property
    def current_option(self) -> str | None:
        g = self.d.get("group")
        return g if g in self.options else None

    async def async_select_option(self, option: str) -> None:
        g = next((g for g in self._in_region() if g["name"] == option), None)
        await self.coordinator.async_command(cmd="group", group=g["id"] if g else option, play=True)
