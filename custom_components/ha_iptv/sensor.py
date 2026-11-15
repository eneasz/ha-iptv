"""Sensors for what is on screen."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity, SensorEntityDescription
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import HaIptvEntity


@dataclass(frozen=True, kw_only=True)
class Desc(SensorEntityDescription):
    value: Callable[[dict], Any]
    attrs: Callable[[dict], dict] | None = None


def _ts(v: float | None) -> datetime | None:
    return datetime.fromtimestamp(v, timezone.utc) if v else None


SENSORS: tuple[Desc, ...] = (
    Desc(key="channel", translation_key="channel", icon="mdi:television-classic",
         value=lambda d: (d.get("channel") or {}).get("name"),
         attrs=lambda d: {"number": (d.get("channel") or {}).get("num"), "icon_url": (d.get("channel") or {}).get("icon")}),
    Desc(key="group", translation_key="group", icon="mdi:folder-multiple", value=lambda d: d.get("group")),
    Desc(key="region", translation_key="region", icon="mdi:earth", value=lambda d: d.get("region")),
    Desc(key="now", translation_key="now", icon="mdi:play-box",
         value=lambda d: (d.get("now") or {}).get("title"),
         attrs=lambda d: {"description": (d.get("now") or {}).get("desc"), "start": _ts((d.get("now") or {}).get("start")),
                          "end": _ts((d.get("now") or {}).get("stop"))}),
    Desc(key="next", translation_key="next", icon="mdi:skip-next-circle",
         value=lambda d: (d.get("next") or {}).get("title"),
         attrs=lambda d: {"start": _ts((d.get("next") or {}).get("start")), "end": _ts((d.get("next") or {}).get("stop"))}),
    Desc(key="ends_at", translation_key="ends_at", device_class=SensorDeviceClass.TIMESTAMP, value=lambda d: _ts((d.get("now") or {}).get("stop"))),
    Desc(key="resolution", translation_key="resolution", icon="mdi:aspect-ratio",
         value=lambda d: (f"{d['tech']['w']}x{d['tech']['h']}" if d.get("tech") else None),
         attrs=lambda d: {k: (d.get("tech") or {}).get(k) for k in ("short", "fps", "vcodec", "acodec", "ach", "verdict")}),
    Desc(key="quality", translation_key="quality", icon="mdi:high-definition-box", value=lambda d: (d.get("tech") or {}).get("short"),
         attrs=lambda d: {"claimed": (d.get("channel") or {}).get("claimed")}),
    Desc(key="survey", translation_key="survey", icon="mdi:clipboard-check-multiple",
         value=lambda d: (round(100 * d["survey"]["done"] / max(1, d["survey"]["total"])) if d.get("survey") else None),
         native_unit_of_measurement="%", attrs=lambda d: d.get("survey") or {}),
)


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities(HaIptvSensor(entry.runtime_data, d) for d in SENSORS)


class HaIptvSensor(HaIptvEntity, SensorEntity):
    entity_description: Desc

    def __init__(self, coordinator, description: Desc) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description

    @property
    def native_value(self):
        return self.entity_description.value(self.d)

    @property
    def extra_state_attributes(self):
        return self.entity_description.attrs(self.d) if self.entity_description.attrs else None
