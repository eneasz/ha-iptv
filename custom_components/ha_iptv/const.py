"""Constants for HA IPTV."""
from __future__ import annotations

DOMAIN = "ha_iptv"
DEFAULT_PORT = 8765

CONF_TOKEN = "token"
CONF_ADB_ENTITY = "adb_entity"        # androidtv media_player used to launch the app over ADB
CONF_TV_ENTITY = "tv_entity"          # TV (or AVR) media_player turned on before launching
CONF_TURN_OFF_TV = "turn_off_tv"      # turn_off also switches the TV off
CONF_DEVICE_NAME = "device_name"      # how the other sticks show this one (e.g. "Living room")

APP_COMPONENT = "pl.home.channeldeck/.MainActivity"

EVENT_CHANNEL_CHANGED = f"{DOMAIN}_channel_changed"
EVENT_PROGRAMME_STARTED = f"{DOMAIN}_programme_started"
EVENT_QUALITY_MISMATCH = f"{DOMAIN}_quality_mismatch"
EVENT_STREAM_ERROR = f"{DOMAIN}_stream_error"
EVENT_WATCH_TRIGGERED = f"{DOMAIN}_watch_triggered"
EVENT_SCHEDULE_TRIGGERED = f"{DOMAIN}_schedule_triggered"

# commands that can ride on a launch intent when the app is not running / in the background
# commands that need the app on screen even when it is only in the background
FRONT = {"vod", "continue"}
LAUNCHABLE = {"play", "region", "group", "zap", "stop", "info"}
