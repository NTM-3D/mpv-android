# mpv for Android (NTM-3D)

This fork of mpv-android focuses on 3D playback for the Lume Pad 2.

Video demo:  
<a href="https://www.youtube.com/watch?v=0vEvsC6QhAA"><img src="https://img.youtube.com/vi/0vEvsC6QhAA/0.jpg" width="240">

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
- Adjustable 3D subtitle depth, position and size

## 3D format detection

The player recognizes these filename markers:

| Format | Auto-detected names |
| --- | --- |
| Half SBS | `hsbs`, `half-sbs`, `half_sbs`, `sbs-half`, `sbs_half`, `half_2x1` |
| Half TAB | `htab`, `half-tab`, `half_tab`, `tab-half`, `tab_half`, `half_1x2`, `half-ou`, `half_ou`, `half-overunder` |
| Full SBS | `fsbs`, `full-sbs`, `full_sbs`, `sbs-full`, `sbs_full`, `_2x1`, `full_2x1` |
| Full TAB | `ftab`, `full-tab`, `full_tab`, `tab-full`, `tab_full`, `_1x2`, `full_1x2`, `full-ou`, `full_ou`, `full-overunder` |

**Generic tokens (default to Half):**
- **SBS:** `3d`, `sbs`
- **TAB/OU:** `tab`, `ou`, `overunder`, `over-under`, `over_under`, `topbottom`, `top-bottom`, `top_bottom`, `tb`

**Special cases:**
- `sv_YYYYMMDD...` XREAL Beam Pro videos → Full SBS

If no format is detected, the player defaults to Half SBS when 3D is enabled manually.

## 3D subtitles

- Text subtitles are rendered as stereo subtitles in 3D mode.
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
