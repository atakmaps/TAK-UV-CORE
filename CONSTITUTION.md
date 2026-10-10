# UV-PRO Constitution

Updated: 2026-10-10

This file is binding. `AGENTS.md`, `docs/DEVELOPER-GUIDE.md`, `CONTRIBUTING.md`, README notes, and Cursor rules describe how to comply with it. When they disagree with this file, this file wins and the other document is wrong.

The maintainer reviews and merges pull requests. `main` is branch-protected. The maintainer may bypass that protection for their own work. Everyone else lands changes through a pull request the maintainer accepts.

## I. One plugin, two radio transports

UV-PRO (`com.uvpro.plugin`) talks to ATAK over two radio links:

- a BTECH UV-PRO radio on Bluetooth SPP / KISS
- a MeshCore companion on BLE

Shared behavior (chat, CoT, directed send, broadcast) belongs on the shared path (`PacketRouter`, `ChatBridge`, `CotBridge`) and must keep working on both links. A pull request that removes either transport has to say that removal is the point of the change.

Wi‑Fi / TAK is a third path (SA relay and uplink). It is not a substitute for either radio.

## II. The ATAK SDK is not in git

The ATAK-CIV SDK is proprietary. It is obtained from the maintainer or through TAK and placed locally. Do not commit `app/libs/atak-civ/`, SDK zip files, or `local.properties`.

## III. Secrets stay out of git

Do not commit tokens, keystores you created, `deploy.env`, phone preference dumps, device serials, or logcats from a real phone.

`app/src/main/assets/atakmaps-ca.p12` and `app/src/main/assets/isrg-root-x1.pem` stay. They are the trust bundle every install needs so ATAK can take plugin updates from `atakmaps.com`. The PKCS#12 store unlock in `strings.xml` (`uvpro_trust_bundle_p12_key`) stays with that bundle.

## IV. Release actions are explicit

Changing `ext.PLUGIN_VERSION`, cutting a TAK submission zip, tagging a release, or publishing to the update server happens only when that is the stated point of the pull request. Do not bump the version or touch the trust chain as a side effect of a bugfix.

## V. Review before merge

Contributors, including AI agents, open a pull request. They do not merge it, force-push `main`, or deploy a signed build. The pull request states what changed, which transport was exercised (UV-PRO, MeshCore, Wi‑Fi, or not run), and whether signing, version, or the update server was touched.

## VI. The MeshCore-only plugin stays where it is

`atakmaps/TAK-MESHCORE` still builds the MeshCore-only plugin and remains published until the maintainer freezes it. New MeshCore work for this project lands in this repository. Do not port a change into `TAK-MESHCORE` unless the maintainer asks.

## VII. Say what you actually ran

A change is not finished because it compiles. The pull request says which build variant was produced and which transports were tried on a device. If only one transport was tried, say so.
