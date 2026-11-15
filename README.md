# Spore Mali

Unofficial Android build of the Spore port (Mali GPU / DXVK 1.9.2b), packaged for this phone.

**Download:** see the [Releases](../../releases) page.

## This build

- Settings panel fits on one screen; Controls moved to a **More** page.
- Picture returns correctly after leaving and returning to the app (DXVK recreates its Vulkan surface).
- Touch layout editor: tap a control to select it, then drag the size bar under **Reset** right to grow, left to shrink (50-200%). Tap empty space to deselect. **Reset** clears positions and sizes.
- Creature editor: double-tap and hold a part, drag it, release to place it.
- Editor **Undo** / **Redo** buttons work (Ctrl+Z / Ctrl+Y sent over separate frames).
- Cursor (touchpad) mode and direct mode both supported.

## Install

`adb install -r spore-vk11-fixed.apk`, or open the APK on the phone.

- Updating this build keeps saves, settings and layout: same signing key as every release here.
- The original third-party APK used a different debug key, so switching from it once requires an uninstall (saves live in `/sdcard/Spore/UserData` and survive that).

## Notes

- Spore and its assets belong to EA / Maxis. This repository only hosts build artifacts of a personal port; no game data is redistributed by the repository itself.
