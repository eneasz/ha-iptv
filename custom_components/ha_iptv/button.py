"""Buttons: zap, info, favourite toggle, reload."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from homeassistant.components.button import ButtonEntity, ButtonEntityDescription
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import HaIptvEntity
from .sync import async_continue


@dataclass(frozen=True, kw_only=True)
class Desc(ButtonEntityDescription):
    cmd: dict[str, Any]


BUTTONS = (
    Desc(key="channel_up", translation_key="channel_up", icon="mdi:chevron-up", cmd={"cmd": "zap", "delta": -1}),
    Desc(key="channel_down", translation_key="channel_down", icon="mdi:chevron-down", cmd={"cmd": "zap", "delta": 1}),
    Desc(key="show_info", translation_key="show_info", icon="mdi:information-outline", cmd={"cmd": "info"}),
    Desc(key="toggle_favourite", translation_key="toggle_favourite", icon="mdi:star", cmd={"cmd": "favourite"}),
    Desc(key="reload", translation_key="reload", icon="mdi:refresh", cmd={"cmd": "refresh"}),
)


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([*(HaIptvButton(entry.runtime_data, d) for d in BUTTONS), ContinueHereButton(entry.runtime_data)])


class HaIptvButton(HaIptvEntity, ButtonEntity):
    entity_description: Desc

    def __init__(self, c, d: Desc) -> None:
        super().__init__(c, d.key)
        self.entity_description = d

    async def async_press(self) -> None:
        await self.coordinator.async_command(**self.entity_description.cmd)
        if self.entity_description.key == "reload":
            self.coordinator.invalidate_channels()
            await self.coordinator.async_refresh_catalog()


class ContinueHereButton(HaIptvEntity, ButtonEntity):
    """Carry on here with whatever another TV was playing, from the same place."""
    _attr_translation_key = "continue_here"
    _attr_icon = "mdi:play-circle-outline"

    def __init__(self, c) -> None:
        super().__init__(c, "continue_here")

    async def async_press(self) -> None:
        await async_continue(self.coordinator.hub, self.coordinator)
