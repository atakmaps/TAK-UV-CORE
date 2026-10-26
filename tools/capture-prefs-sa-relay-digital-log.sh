#!/usr/bin/env bash
# Guided log capture for UV-PRO issues after exiting Tool Preferences:
#   - beacons continue on digital channel ~30s then TX timeout (radio + plugin)
#   - beacons fall back to CH01; dual watch turns off
#   - disabling SA Relay in prefs restores digital-channel TX
#
# Writes a timestamped log bundle to the user's Desktop when finished.
#
# Usage:
#   ./tools/capture-prefs-sa-relay-digital-log.sh
#   ./tools/capture-prefs-sa-relay-digital-log.sh DEVICE_SERIAL
#   DEVICE_SERIAL=<adb-serial> ./tools/capture-prefs-sa-relay-digital-log.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [[ -d "${HOME}/Desktop" ]]; then
  OUT_DIR="${HOME}/Desktop"
elif [[ -d "${HOME}/desktop" ]]; then
  OUT_DIR="${HOME}/desktop"
else
  OUT_DIR="${REPO_ROOT}/logs"
fi

SERIAL="${1:-${DEVICE_SERIAL:-}}"
ADB=(adb)
if [[ -n "${SERIAL}" ]]; then
  ADB=(adb -s "${SERIAL}")
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found. Install Android platform-tools (adb) and ensure it is on PATH."
  exit 1
fi

DEVICE_COUNT="$("${ADB[@]}" devices 2>/dev/null | awk 'NR>1 && $2=="device" {c++} END {print c+0}')"
if [[ "${DEVICE_COUNT}" -eq 0 ]]; then
  echo "ERROR: No Android device detected. Enable USB debugging and accept the RSA prompt on the phone."
  "${ADB[@]}" devices -l || true
  exit 1
fi

if [[ -z "${SERIAL}" && "${DEVICE_COUNT}" -gt 1 ]]; then
  echo "Multiple devices connected — pick one:"
  "${ADB[@]}" devices -l
  echo
  read -r -p "Enter device serial: " SERIAL
  if [[ -z "${SERIAL}" ]]; then
    echo "ERROR: Serial required when more than one device is connected."
    exit 1
  fi
  ADB=(adb -s "${SERIAL}")
fi

if [[ -z "${SERIAL}" ]]; then
  SERIAL="$("${ADB[@]}" get-serialno 2>/dev/null | tr -d '\r')"
fi

detect_atak_package() {
  local pkg=""
  for candidate in com.atakmap.app.civ com.atakmap.app.mil com.atakmap.app.gov; do
    if "${ADB[@]}" shell pm path "${candidate}" 2>/dev/null | grep -q package; then
      pkg="${candidate}"
      break
    fi
  done
  echo "${pkg}"
}

ATAK_PKG="$(detect_atak_package)"
if [[ -z "${ATAK_PKG}" ]]; then
  echo "WARNING: Could not detect ATAK package (civ/mil/gov). Pref dumps may be incomplete."
  ATAK_PKG="com.atakmap.app.civ"
fi

STAMP="$(date +%Y%m%d_%H%M%S)"
MODEL="$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' | tr ' ' '_')"
ANDROID="$("${ADB[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
BASE_NAME="uvpro_prefs_sa_relay_digital_${STAMP}_${SERIAL}_${MODEL}"
LOG_FILE="${OUT_DIR}/${BASE_NAME}.log"
SUMMARY_FILE="${OUT_DIR}/${BASE_NAME}_summary.txt"
META_FILE="${OUT_DIR}/${BASE_NAME}_meta.txt"
POINTER="${REPO_ROOT}/logs/.active_prefs_sa_relay_capture"

mkdir -p "${OUT_DIR}" "${REPO_ROOT}/logs"
echo "${LOG_FILE}" > "${POINTER}"

mark_phase() {
  local label="$1"
  local note="${2:-}"
  {
    echo ""
    echo "======== PHASE: ${label} ========"
    echo "# time: $(date -Is)"
    if [[ -n "${note}" ]]; then
      echo "# note: ${note}"
    fi
    echo ""
  } >> "${LOG_FILE}"
  echo "  → marked phase: ${label}"
}

append_block() {
  local title="$1"
  local content="$2"
  {
    echo ""
    echo "----- ${title} -----"
    echo "${content}"
  } >> "${LOG_FILE}"
}

dump_uvpro_prefs() {
  local label="$1"
  local out=""
  if out="$("${ADB[@]}" exec-out run-as "${ATAK_PKG}" sh -c \
      'grep -h uvpro shared_prefs/*.xml 2>/dev/null || true' 2>/dev/null | tr -d '\r')"; then
    : # ok
  elif out="$("${ADB[@]}" shell "grep -h uvpro /data/data/${ATAK_PKG}/shared_prefs/*.xml 2>/dev/null" \
      2>/dev/null | tr -d '\r')"; then
    : # ok
  else
    out="(unable to read ATAK prefs — run-as may be blocked on this build)"
  fi
  append_block "UV-PRO prefs (${label})" "${out:- (empty)}"
}

collect_device_meta() {
  local plugin_info atak_info bt_info activity_info
  plugin_info="$("${ADB[@]}" shell dumpsys package com.uvpro.plugin 2>/dev/null \
    | grep -E 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime=' \
    | head -20 | tr -d '\r' || true)"
  atak_info="$("${ADB[@]}" shell dumpsys package "${ATAK_PKG}" 2>/dev/null \
    | grep -E 'versionName=|versionCode=' | head -5 | tr -d '\r' || true)"
  bt_info="$("${ADB[@]}" shell dumpsys bluetooth_manager 2>/dev/null \
    | grep -Ei 'state=|name=|connected|bond|UV-PRO|BTECH|Mesh' | head -40 | tr -d '\r' || true)"
  activity_info="$("${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
    | grep -E 'mResumedActivity|topResumedActivity|ATAK|Settings|uvpro' | head -20 | tr -d '\r' || true)"

  {
    echo "UV-PRO SA Relay / digital channel capture"
    echo "Started: $(date -Is)"
    echo "Device serial: ${SERIAL}"
    echo "Model: ${MODEL}"
    echo "Android: ${ANDROID}"
    echo "ATAK package: ${ATAK_PKG}"
    echo "Output log: ${LOG_FILE}"
    echo ""
    echo "--- UV-PRO plugin package ---"
    echo "${plugin_info:- (not installed or dumpsys failed)}"
    echo ""
    echo "--- ATAK package ---"
    echo "${atak_info:- (unknown)}"
    echo ""
    echo "--- Bluetooth (snippet) ---"
    echo "${bt_info:- (none)}"
    echo ""
    echo "--- Activity (snippet) ---"
    echo "${activity_info:- (none)}"
  } > "${META_FILE}"

  append_block "DEVICE META" "$(cat "${META_FILE}")"
  dump_uvpro_prefs "baseline"
}

wait_enter() {
  local prompt="$1"
  read -r -p "${prompt}" _
}

echo "=============================================="
echo " UV-PRO prefs / SA Relay / digital channel log"
echo "=============================================="
echo " Device : ${SERIAL} (${MODEL}, Android ${ANDROID})"
echo " ATAK   : ${ATAK_PKG}"
echo " Output : ${LOG_FILE}"
echo " Summary: ${SUMMARY_FILE}"
echo
echo " BEFORE YOU START (on the phone):"
echo "  • USB debugging on, phone unlocked, ATAK running with UV-PRO plugin loaded."
echo "  • UV-PRO radio connected over Bluetooth."
echo "  • Digital-only / digital channel mode WORKING (beacons on the digital channel)."
echo "  • SA Relay ENABLED (Settings → Tool Preferences → UV-PRO → SA Relay)."
echo "  • Dual watch ON if the user normally uses it."
echo "  • Wi‑Fi or TAK network connected so SA Relay has inbound traffic (if applicable)."
echo
echo " This script will prompt you through timed steps while logcat runs."
echo " Leave this terminal visible and follow each step on the phone."
echo
wait_enter "Press ENTER when the phone is ready for BASELINE capture... "

{
  echo "# UV-PRO prefs / SA Relay / digital channel capture"
  echo "# started: $(date -Is)"
  echo "# device: ${SERIAL}"
  echo "# model: ${MODEL}"
  echo "# android: ${ANDROID}"
  echo "# atak: ${ATAK_PKG}"
  echo "# plugin: com.uvpro.plugin"
  echo "#"
} > "${LOG_FILE}"

collect_device_meta
mark_phase "BASELINE" "ATAK open; digital channel OK; SA Relay ON; issue not yet reproduced"

"${ADB[@]}" logcat -c

# UV-PRO plugin tags + radio/BT stack + activity lifecycle + global errors
LOG_TAGS=(
  UVPro:V UVPro.BT:V UVPro.MeshBLE:V UVPro.MeshBLEProbe:V UVPro.UI:V
  UVPro.CotBridge:V UVPro.RadioCtrl:V UVPro.RadioSvc:V UVPro.RadioGps:V
  UVPro.Router:V UVPro.KissFreq:V UVPro.SlottedTx:V UVPro.ChatBridge:I
  UVPro.APRS.TX:I UVPro.APRS.MsgTx:I UVPro.PingReply:I UVPro.PositionReq:I
  UVPro.CotBuilder:I UVPro.Fragment:I UVPro.Crypto:I UVPro.NetSlot:I
  UVPro.RfTakUplink:I UVPro.WifiKeepalive:I UVPro.Reachability:I
  BluetoothAdapter:I BluetoothDevice:I BluetoothGatt:I BluetoothGattCallback:I
  ActivityTaskManager:I ActivityManager:I WindowManager:I
)

echo "Log capture running..."
"${ADB[@]}" logcat -v threadtime "${LOG_TAGS[@]}" >> "${LOG_FILE}" &
LOG_PID=$!

# Secondary stream: all ERROR lines (TX timeout may surface outside UVPro tags)
"${ADB[@]}" logcat -v threadtime *:E >> "${LOG_FILE}.errors" &
ERR_PID=$!

cleanup() {
  if kill -0 "${LOG_PID}" 2>/dev/null; then
    kill "${LOG_PID}" 2>/dev/null || true
    wait "${LOG_PID}" 2>/dev/null || true
  fi
  if kill -0 "${ERR_PID}" 2>/dev/null; then
    kill "${ERR_PID}" 2>/dev/null || true
    wait "${ERR_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo
echo "STEP 1 — Open Tool Preferences"
echo "  ATAK → Settings → Tool Preferences → UV-PRO (or Plugin Settings)."
echo "  Do NOT change anything yet — just open the screen."
wait_enter "Press ENTER when UV-PRO Tool Preferences are OPEN... "
mark_phase "PREFS_OPEN" "UV-PRO Tool Preferences visible"
dump_uvpro_prefs "prefs_open"

echo
echo "STEP 2 — Exit preferences (start reproduction)"
echo "  Press BACK until you are back on the ATAK map / UV-PRO panel."
echo "  Do not change SA Relay yet."
wait_enter "Press ENTER immediately AFTER leaving preferences... "
mark_phase "PREFS_EXIT" "User exited Tool Preferences — expect ~30s digital beacons then TX timeout"

echo
echo "STEP 3 — Wait through the failure window (~45 seconds)"
echo "  Watch the phone and radio:"
echo "    • continued beacons on digital channel"
echo "    • TX timeout on radio and/or plugin"
echo "    • beacons may move to CH01; dual watch may turn off"
echo "  Stay on the map — do not open other apps."
sleep 45
mark_phase "POST_FAILURE" "~45s after prefs exit — failure window"
dump_uvpro_prefs "post_failure"
append_block "Activity after failure" \
  "$("${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
    | grep -E 'mResumedActivity|topResumedActivity' | head -5 | tr -d '\r' || true)"

echo
echo "STEP 4 — Disable SA Relay"
echo "  Open Tool Preferences → UV-PRO again."
echo "  Turn OFF SA Relay (and leave other settings as-is)."
echo "  Exit back to the map."
wait_enter "Press ENTER after SA Relay is OFF and you are back on the map... "
mark_phase "SA_RELAY_OFF" "User disabled SA Relay and exited prefs"
dump_uvpro_prefs "sa_relay_off"

echo
echo "STEP 5 — Observe recovery (~30 seconds)"
echo "  Confirm whether digital-channel beacons resume."
sleep 30
mark_phase "RECOVERY" "~30s after SA Relay disabled"
dump_uvpro_prefs "recovery"

wait_enter "Press ENTER to STOP capture and write summary... "

cleanup
trap - EXIT INT TERM

if [[ -f "${LOG_FILE}.errors" ]]; then
  {
    echo ""
    echo "======== ERROR-LEVEL LOGCAT (merged tail) ========"
    tail -n 500 "${LOG_FILE}.errors"
  } >> "${LOG_FILE}"
  rm -f "${LOG_FILE}.errors"
fi

{
  echo "# ended: $(date -Is)"
} >> "${LOG_FILE}"

{
  echo "UV-PRO prefs / SA Relay / digital channel capture summary"
  echo "Device: ${SERIAL} ${MODEL} Android ${ANDROID}"
  echo "ATAK: ${ATAK_PKG}"
  echo "Log: ${LOG_FILE}"
  echo "Ended: $(date -Is)"
  echo
  echo "--- Phase markers ---"
  grep -E '^======== PHASE:' "${LOG_FILE}" 2>/dev/null || true
  echo
  echo "--- SA Relay ---"
  grep -Ei 'SA Relay|sa_relay|maybeSaRelay' "${LOG_FILE}" 2>/dev/null | tail -80 || true
  echo
  echo "--- Beacons / OPENRL ---"
  grep -Ei 'beacon|OPENRL|sendPosition|Periodic beacon|Startup beacon|Smart beacon' "${LOG_FILE}" 2>/dev/null | tail -80 || true
  echo
  echo "--- Digital channel / KISS lock ---"
  grep -Ei 'digital|KISS frequency|KissFreq|autoShareLocCh|setDigitalChannel|Digital only' "${LOG_FILE}" 2>/dev/null | tail -80 || true
  echo
  echo "--- Dual watch / radio snapshot ---"
  grep -Ei 'dual watch|dual=|setDualWatch|snapshot settings|snapshot fast' "${LOG_FILE}" 2>/dev/null | tail -80 || true
  echo
  echo "--- TX / KISS / CoT send ---"
  grep -Ei 'Sent KISS|TX blocked|CoT double-send|Sending CoT|Sending GPS beacon|timeout|time out|Not connected' "${LOG_FILE}" 2>/dev/null | tail -80 || true
  echo
  echo "--- RF arbitration / slotted TX ---"
  grep -Ei 'RfTxArbitrator|SlottedTx|OPENRL guard|deferred' "${LOG_FILE}" 2>/dev/null | tail -40 || true
  echo
  echo "--- Bluetooth / connection ---"
  grep -Ei 'UVPro\.BT|connection lost|Connected to|disconnect|ACL' "${LOG_FILE}" 2>/dev/null | tail -60 || true
  echo
  echo "--- Errors (E level) ---"
  grep -E ' E ' "${LOG_FILE}" 2>/dev/null | tail -60 || true
} > "${SUMMARY_FILE}"

echo
echo "Done."
echo "  Full log : ${LOG_FILE}"
echo "  Summary  : ${SUMMARY_FILE}"
echo "  Meta     : ${META_FILE}"
echo "  Pointer  : ${POINTER}"
wc -l "${LOG_FILE}" 2>/dev/null || true
echo
echo "Email or upload the .log and _summary.txt files to your support contact."
