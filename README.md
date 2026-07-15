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
- Automatic 3D format detection from filenames.
- Manual 3D mode selection and swap-eyes control.
- Advanced 3D subtitle support for both text and image-based subtitles.
- Per-file 3D settings memory: Image subtitle position, scale, X/Y stretch, and swap-eyes state are saved individually for each file.
- Network subtitle auto-guessing: Automatically attempts to load external subtitles, when opening from SMB shares via file explorers (e.g., CX File Explorer), based on the media filename.
- Image file playback: Open images directly from file explorers (e.g., CX File Explorer).

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

## 3D Subtitles

The player provides advanced controls for subtitles in 3D mode via the 3D settings dialog:
- Text Subtitles: Rendered as custom stereo bitmaps with adjustable depth, position, and overall scale.
- Image Subtitles (PGS, VobSub, DVD, etc.): 
  - Pre-authored 3D: If the subtitle already contains stereo pairs, it is rendered natively with correct positioning.
  - Mono Duplication: If the subtitle is mono, it can be automatically duplicated into both eyes to match the current SBS/TAB packing.
  - Independent Scaling: Dedicated X and Y scale sliders to correct aspect ratio stretching caused by 3D packing (e.g., 0.5x on the squeezed axis).

## Network Subtitle Guessing

When playing media from SMB shares via file explorers (e.g., CX File Explorer), the player will automatically attempt to load external subtitles from the same directory. It checks for:
1. Exact name matches: `movie.srt`, `movie.ass`, `movie.vtt`, etc.
2. Language matches: `movie.en.srt`, `movie.eng.srt`, `movie.forced.srt`, `movie.sdh.srt`, etc.

| Format | Auto-detected names |
| --- | --- |
| Extensions | `srt`, `ass`, `ssa`, `txt` |
| Wildcards | `en`, `eng`, `es`, `spa`, `fr`, `fre`, `de`, `ger`, `it`, `ita`, `pt`, `por`, `ru`, `rus`, `zh`, `chi`, `jp`, `jpn`, `ko`, `kor`, `ar`, `ara`, `hi`, `hin`, `sv`, `se`, `fi`, `no`, `dk`, `forced`, `sdh`, `cc`, `default` |

## Image File Playback

You can now open image files (`.jpg`, `.jpeg`, `.png`, `.webp`, `.bmp`, `.gif`) directly from Android file explorers. If the image filename contains 3D markers (e.g., `photo_hsbs.jpg`), it will automatically render in the correct 3D mode, just like a video file.

---
*Note: This fork includes custom `mpv` patches (`osd-keepaspect` and `image-subs-scale-x/y`) to ensure perfect subtitle positioning and independent axis scaling in 3D modes.*