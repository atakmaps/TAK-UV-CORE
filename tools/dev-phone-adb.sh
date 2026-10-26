#!/usr/bin/env bash
set -euo pipefail

# Set DEVICE_SERIAL in the environment. Do not commit a phone serial.
DEV_PHONE_SERIAL="${DEVICE_SERIAL:-}"
if [[ -z "${DEV_PHONE_SERIAL}" ]]; then
  echo "Set DEVICE_SERIAL to the adb serial from 'adb devices'." >&2
  exit 1
fi

if [[ "${1:-}" == "--serial" ]]; then
  echo "${DEV_PHONE_SERIAL}"
  exit 0
fi

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <adb args...>"
  echo "Example: $0 install -r app/build/outputs/apk/civ/debug/<apk>"
  exit 1
fi

exec adb -s "${DEV_PHONE_SERIAL}" "$@"
