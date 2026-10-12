Updated: 2026-10-12

## Goal
Mesh ATAK traffic stays a flood on channel 7 (`ATAK_DATA`). Cut dropped deliveries by sending raw AX.25 in one LoRa packet instead of base64 text chunks.

## Done
- `feature/meshcore-firmware-out-path` and its stash are deleted. Do not recreate them.
- Branch `feature/mesh-single-packet` from `main` @ `d2addf3`. Field-proven fallback `ab4c43c` (2026-10-12). Earlier rollback `ef3679a`. civDebug built and installed on both phones. Reload the plugin on each phone. Do not force-stop ATAK.
- Mesh TX: a point is three single channel packets (127-byte cap), sent 2.5s apart. The ACK waits 3s so the last flood can finish, then holds the channel 2.5s so a GPS beacon cannot step on it. Mesh CoT retry is 20s. `path_len` stays `0xFF`. UV-PRO 240-byte split unchanged. Fallback: `ab4c43c`.
- When a field log shows a delivery problem, fix it and tell Paul to reload and retest. Do not stop at the report.
- Inbound unknown/neutral points keep their CoT type and callsign. Do not rewrite them to `b-m-p-s-p-i` / "Point".
- Mesh RX still accepts old `UVAX1|` text. UV-PRO send path is unchanged. Mesh skips the 3-second CoT double-send.

## Next
- Paul reloads the plugin on both phones. Watch log `TX ATAK_DATA ... chunks=1`.
- Radio silence long-press now sets both UV-PRO and MeshCore. Mesh air sends (channel data, channel text, DMs, CLI, room login, advert, node discover) return without transmitting. Ping and manual beacon only log “sent” when the radio accepted the frame.
- Do not push unless Paul asks. Do not field-test `feature/meshcore-stock-routing`.

## Last user commit
Last user commit (Darksteal): ab4c43c — Oct 12 — "commit all changes"

## Pointers
- Synced handoff: `~/dev/agent-handoffs/Darksteal-MeshCore-Stock-Routing.md`
- APK: `app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-2.0.19-d2addf33-5.6.0-civ-debug.apk`
- Never force-stop ATAK after install

## Send a GeoChat from an open window (ADB)

Paul leaves the GeoChat conversation open on the phone. Do not broadcast `SEND_MESSAGE`. Type into the focused box and tap Send. Confirmed 2026-10-12: DEV-ONE chat with DEV-TWO, text `meshtest1`, log `TX ATAK_DATA ch=7 ax25=161 bytes chunks=1`. Paul confirmed DEV-TWO received it.

1. Dump the UI: `adb -s <serial> shell uiautomator dump /sdcard/uidump.xml` then read it. The open room is the title (`DEV-TWO`). The field is `com.atakmap.app.civ:id/messageBox`. The button is `com.atakmap.app.civ:id/sendButton`.
2. Clear the field with KEYCODE_DEL (`input keyevent 67`) in one shell loop. Type letters and digits only. `input text` turns `%s` into a space and mangles any other `%` (`mesh%stest%1` became `mesh test%1l`).
3. The keyboard shrinks the window and moves Send. Dump again and tap the center of the new `sendButton` bounds. Do not reuse the pre-keyboard coordinates.
4. Success: the line shows under "Me:" in that chat, and logcat has `Sending chat over radio` plus `TX ATAK_DATA ... chunks=1`. `TX blocked by Radio Silence` means the flag is still on.
