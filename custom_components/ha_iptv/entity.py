"""Shared entity base."""
from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import HaIptvCoordinator


class HaIptvEntity(CoordinatorEntity[HaIptvCoordinator]):
    _attr_has_entity_name = True

    def __init__(self, coordinator: HaIptvCoordinator, key: str) -> None:
        super().__init__(coordinator)
        eid = coordinator.entry.entry_id
        self._attr_unique_id = f"{eid}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, eid)}, name=coordinator.entry.title or "HA IPTV", manufacturer="HA IPTV",
            model="Fire TV app", sw_version=(coordinator.data or {}).get("app"))

    @property
    def d(self) -> dict:
        return self.coordinator.data or {}

    @property
    def available(self) -> bool:
        return True   # offline is a meaningful state (TV off), entities report it instead of going unavailable
