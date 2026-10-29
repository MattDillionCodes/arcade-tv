# Arcade

A dashboard-style Android TV / Google TV launcher, inspired by Windows Phone's Start screen.
Kotlin, plain Views, one dependency (ZXing core, for the QR code). Android TV 9+.

```bash
./install.sh                        # first connected device or emulator
TV=192.168.68.52:5555 ./install.sh  # a TV over ADB Wi-Fi
```

## What's on screen

- **Panorama**: two sections, *start* and *apps*, under a huge lowercase title that scrolls at a
  third of the content's speed. Moving into *apps* snaps the whole section into view.
- **Start**: tiles flow column by column into a 4-row grid (small 1x1, medium 2x2, wide 4x2,
  large 4x4), Windows Phone style.
  - **Hero tile** (large): the pinned app, or `pickHero()` in `Apps.kt` decides.
  - **Weather** (wide, live): now, then turns over to the next three days. Open-Meteo, no key;
    location is Los Angeles (`Weather.kt`).
  - **Clock** (medium, live): time and weekday, then the date.
  - **Small tiles**: Action Center, wallpaper from phone, inputs, all apps.
  - **Pinned apps** (medium): the first eight A–Z until you pin your own.
- **Apps**: everything, A to Z, as Windows Phone's app list.

## Controls

| Button | Does |
|---|---|
| Up from the top row, Menu, gamepad Y | Action Center |
| Hold OK, gamepad X | Options for the focused app: make hero, colour, move, pin/unpin |
| Back | Close whatever is open, or return to the hero tile |
| Home | Back to the hero tile |

## Action Center

Pulls down from the top like Windows Phone 8.1's: quick actions (network, bluetooth, display,
sound, settings), volume, HDMI inputs (hidden when there are none, e.g. on the emulator),
accent colour (the 20 Windows Phone accents), tile style (*solid*, or *photo*: the wallpaper
shows through the tiles, WP 8.1's "show background through tiles"), and wallpaper.

## Wallpapers from your phone

*Wallpaper → from phone* (or the picture tile) shows a QR code. Scan it, choose photos, and they
appear on the TV. The phone crops each one to 1920x1080 JPEG before uploading, which is the
cheapest format for a 1080p TV UI to draw.

The upload server only runs while that screen is open, and each QR code carries a fresh random
token. Photos are saved in `/sdcard/Android/data/com.matthew.arcade/files/wallpapers/`;
`adb push` into that folder works too.

**On the emulator** the TV sits behind the emulator's NAT, so a phone can't reach it. Forward
the port and use the Mac's browser instead (the QR screen shows the exact URL):

```bash
adb forward tcp:8765 tcp:8765   # then open http://localhost:8765/<token>
```
