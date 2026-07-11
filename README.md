# mpv for Android (NTM-3D)

This fork of mpv-android focuses on 3D playback for Android devices that support Leia-style stereo rendering.

## Credits

This fork is based on:

- [mpv-android/mpv-android](https://github.com/mpv-android/mpv-android)
- [jakedowns/mpv-android](https://github.com/jakedowns/mpv-android)

## What this fork adds

- 3D playback support for:
  - Half SBS
  - Half TAB
  - Full SBS
  - Full TAB
- Automatic 3D format detection from filenames
- Manual 3D mode selection
- Swap-eyes control
- Adjustable 3D subtitle depth
- Stereo subtitle rendering

## 3D format detection

The player recognizes these filename markers:

| Format | Auto-detected names |
| --- | --- |
| Half SBS | `hsbs`, `half-sbs`, `half_sbs`, `sbs-half`, `sbs_half`, `half_2x1`, `sbs` |
| Half TAB | `htab`, `half-tab`, `half_tab`, `tab-half`, `tab_half`, `half_1x2`, `tab`, `ou`, `overunder`, `over-under`, `over_under`, `topbottom`, `top-bottom`, `top_bottom`, `tb` |
| Full SBS | `fsbs`, `full-sbs`, `full_sbs`, `sbs-full`, `sbs_full`, `full_2x1`, camera-style `sv_YYYYMMDD...` filenames |
| Full TAB | `ftab`, `full-tab`, `full_tab`, `tab-full`, `tab_full`, `full_1x2`, `full-ou`, `full_ou`, `full-overunder` |

If no format is detected, the player defaults to Half SBS when 3D is enabled manually.

## 3D subtitles

- Text subtitles are rendered as stereo subtitles in 3D mode.
- Subtitle depth is adjustable from `-10` to `+10`.
- Image subtitles such as PGS, DVD, DVB, VobSub, and XSub are not supported in 3D as of now.

## Building

Use the project build script:

```sh
cd buildscripts
./buildall.sh
```

For a clean build:

```sh
./buildall.sh --clean
```

See [buildscripts/README.md](buildscripts/README.md) for more details.

## Intent support

This fork keeps mpv-android's intent-based playback support. See [docs/intent.html](docs/intent.html) for the current package name and intent parameters.
