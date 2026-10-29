# Security

Please report security problems privately through GitHub: on this repository's **Security** tab,
choose **Report a vulnerability**. Don't open a public issue for them.

The part most worth a look is the wallpaper upload server (`WallpaperDrop.kt`): it listens on the
local network only while the QR screen is open, behind a random per-session token.
