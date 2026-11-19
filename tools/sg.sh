#!/usr/bin/env bash
# Bonsai Ahead dev harness (macOS). DEV = the emulator borrowed with the factory's emu_borrow.py.
#   tools/sg.sh cmd NAME [ARG]   debug broadcast
#   tools/sg.sh shot OUT.png     spatial screencap 2560 x 1440 (pico-cli capture screenshot)
#   tools/sg.sh log [REGEX]      logcat of tag BonsaiAhead
#   tools/sg.sh install          install the debug APK and start the app
set -u
DEV="${DEV:-emulator-5560}"
PKG=io.embodit.bonsai
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  shot) pico-cli capture screenshot --device "$DEV" --spatial --size 2560x1440 --replace --out "$2" >/dev/null ;;
  log) "$ADB" -s "$DEV" logcat -d -s BonsaiAhead:V | grep -E "${2:-.}" ;;
  install) "$ADB" -s "$DEV" install -r -d "$HERE/../app/build/outputs/apk/debug/app-debug.apk" && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 ;;
  *) sed -n 2,7p "$0" ;;
esac
