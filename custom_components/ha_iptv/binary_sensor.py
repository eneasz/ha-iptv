"""Binary sensors: app reachable, claimed-vs-actual quality mismatch."""
from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import HaIptvEntity


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    c = entry.runtime_data
    async_add_entities([Reachable(c), Mismatch(c), StreamProblem(c)])


class Reachable(HaIptvEntity, BinarySensorEntity):
    _attr_translation_key = "connected"
    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY

    def __init__(self, c) -> None:
        super().__init__(c, "connected")

    @property
    def is_on(self) -> bool:
        return not self.d.get("offline")


class Mismatch(HaIptvEntity, BinarySensorEntity):
    _attr_translation_key = "quality_mismatch"
    _attr_device_class = BinarySensorDeviceClass.PROBLEM

    def __init__(self, c) -> None:
        super().__init__(c, "quality_mismatch")

    @property
    def is_on(self) -> bool:
        return bool((self.d.get("tech") or {}).get("mismatch"))

    @property
    def extra_state_attributes(self):
        t = self.d.get("tech") or {}
        return {"claimed": (self.d.get("channel") or {}).get("claimed"), "actual": t.get("short"), "verdict": t.get("verdict")}


class StreamProblem(HaIptvEntity, BinarySensorEntity):
    _attr_translation_key = "stream_problem"
    _attr_device_class = BinarySensorDeviceClass.PROBLEM

    def __init__(self, c) -> None:
        super().__init__(c, "stream_problem")

    @property
    def is_on(self) -> bool:
        return (bool(self.d.get("error")) or self.d.get("picture", "ok") != "ok") and not self.d.get("offline")

    @property
    def extra_state_attributes(self):
        return {"error": self.d.get("error"), "picture": self.d.get("picture", "ok")}
