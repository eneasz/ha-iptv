# HA IPTV – Android app

> Player only. No channels, playlists or provider are included or endorsed – bring your own legitimately obtained subscription. See the top-level README disclaimer.

Android TV / Fire TV live-TV player for Xtream Codes providers, built for lists with thousands of channels.

- Channels grouped by provider category; the group list is yours to reorder.
- Remembers last group, last channel and group order across restarts.
- Remote: UP/DOWN zap in the playing group; LEFT = groups, OK/RIGHT = channels;
  on a group: MENU or hold OK = move mode (UP/DOWN move, LEFT top, RIGHT bottom, OK save, BACK cancel);
  on channels: MENU = reload list from provider; BACK closes the overlay.

## Install / configure (from hp2)
    adb connect <stick-ip>:5555
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n pl.home.channeldeck/.MainActivity \
        --es server http://HOST:PORT --es user USER --es pass PASS   # your own account
(or type them into the first-run form). Credentials live only in the app's private storage.

## Develop
    export JAVA_HOME=~/android-dev/jdk17 ANDROID_HOME=~/android-dev/sdk
    ~/android-dev/gradle-8.9/bin/gradle :app:assembleDebug
Mock provider (7k channels, 48 groups): `python3 mock/mock_xtream.py 8099`, then
`adb reverse tcp:8099 tcp:8099` and configure server http://127.0.0.1:8099 (any user/pass).
Never put real credentials in this folder.
