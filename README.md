# Spore Mali

Unofficial Android build of the Spore port by [VinTeRuS](https://github.com/VinTeRuS/spore-re), built for a Mali-G57
phone with the Vulkan 1.1 / DXVK 1.9.2b path.

**Download:** [Releases](../../releases) — APK only, no game files.

## This build

- Settings panel fits on one screen; the movement stick button is now **Controls** on the **More** page.
- Screen and Safe zone have an **Auto** option: the picture fills the window at its own ratio inside the system's
  cutout and rounded corners. A manual ratio or margin applies after a restart, so it never corrupts the running
  picture.
- Picture returns correctly after leaving and returning to the app (DXVK recreates its Vulkan surface).
- Touch layout editor: tap a control to select it, then drag the size bar under **Reset** right to grow, left to
  shrink (50-200%). Tap empty space to deselect. **Reset** clears positions and sizes.
- Creature editor: double-tap and hold a part, drag it, release to place it.
- Editor **Undo** / **Redo** buttons work (Ctrl+Z / Ctrl+Y sent over separate frames).
- **Cursor** mode (renamed from Touchpad): dragging moves a pointer and a joystick in the bottom right corner nudges
  it; **Direct** mode acts where the finger lands. Editors always use the cursor.
- The layout editor includes the joystick: tap it and **Hide** keeps it off the screen (red in the editor),
  **Restore** brings it back. **Joystick speed** sets how fast it moves the cursor.

## Source

[`spore-re-0.1.2/`](spore-re-0.1.2) is the port tree these builds come from, for modders. It is the upstream project
plus the changes above. Read its [README](spore-re-0.1.2/README.md) for requirements and the rough build steps.

- No game files and no recompiled game code are stored here.
- The APK in Releases is the built artifact and includes the recompiled game code generated from the maintainer's own
  copy of the game.
- Patches for DXVK and SDL2: [`spore-re-0.1.2/android/patches/`](spore-re-0.1.2/android/patches).

## Install

`adb install -r spore-vk11-fixed.apk`, or open the APK on the phone.

- Updating this build keeps saves, settings and layout: same signing key as every release here.
- The original third-party APK used a different debug key, so switching from it once requires an uninstall (saves
  live in `/sdcard/Spore/UserData` and survive that).

## Legal

Spore and its assets belong to EA / Maxis. This is an unofficial port, not affiliated with EA or Maxis. You need your
own copy of Spore. The port tree keeps the upstream project's terms.
