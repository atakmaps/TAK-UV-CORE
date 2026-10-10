# GitHub Copilot

The project guide is [`AGENTS.md`](../AGENTS.md). Read it before suggesting code. Policy is [`CONSTITUTION.md`](../CONSTITUTION.md). How the plugin is built is [`docs/DEVELOPER-GUIDE.md`](../docs/DEVELOPER-GUIDE.md).

UV-PRO is one ATAK plugin with two radios (BTECH UV-PRO over Bluetooth SPP, MeshCore over BLE). Shared chat and CoT behavior goes through `PacketRouter`, `ChatBridge`, and `CotBridge`. Do not remove the update-server trust setup or commit the ATAK SDK.
