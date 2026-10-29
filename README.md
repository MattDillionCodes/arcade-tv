# Arcade

A dashboard-style Android TV / Google TV launcher, inspired by Windows Phone's Start screen.
Kotlin, plain Views, one dependency (ZXing core, for the QR code). Android TV 9+.

```bash
./install.sh                        # first connected device or emulator
TV=192.168.68.61:5555 ./install.sh  # a TV over ADB Wi-Fi
```

## What's on screen

- **Panorama**: two sections, *Start* and *Apps*, that scroll under a time-of-day greeting, the
  date, the weather and the clock, which all stay put. Moving into *Apps* snaps the whole section
  into view.
  Every caption is in Title Case.
- **Start**: tiles flow column by column into a 4-row grid (small 1x1, medium 2x2, wide 4x2,
  large 4x4), Windows Phone style.
  - **Hero tile** (large): the pinned app, or `pickHero()` in `Apps.kt` decides.
  - **Weather** (wide, live): now, then turns over to the next three days. Open-Meteo, no key.
    Press it (or *Weather* in Action Center) to choose your city; Los Angeles until you do.
  - **Clock** (medium, live): time and weekday, then the date.
  - **Small tiles**: Action Center, wallpaper from phone, inputs, all apps.
  - **Pinned apps**: your eight likely favourites until you pin your own. Hold OK → *Size* cycles
    each one through medium, small (icon only) and wide.
- **Apps**: everything, A to Z, grouped under accent-coloured letter headings like Windows Phone's
  app list.

## Controls

| Button | Does |
|---|---|
| Up from the top row, Menu, gamepad Y | Action Center |
| Hold OK, gamepad X | Options for the focused app: size, make hero, colour, move, pin/unpin |
| Back | Close whatever is open, or return to the hero tile |
| Home | Back to the hero tile |

## Action Center

Pulls down from the top like Windows Phone 8.1's: quick actions (network, bluetooth, display,
sound, settings), volume, HDMI inputs (hidden when there are none, e.g. on the emulator),
accent colour (the 20 Windows Phone accents), tile style (*solid*, or *photo*: the wallpaper
shows through the tiles, WP 8.1's "show background through tiles"), wallpaper, weather city, and
the screensaver timer.

## Screensaver

After 5 idle minutes (or 15, 30, or off, in Action Center) Arcade shows a lock screen like Windows
Phone's: your wallpaper with a big clock, the date and the weather. The text drifts a little every
minute to avoid burn-in. Any button wakes it, and that press does nothing else.

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
