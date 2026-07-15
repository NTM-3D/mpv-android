#!/bin/bash -e

. ../../include/path.sh

build=_build$ndk_suffix

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf $build
	exit 0
else
	exit 255
fi

# Local patches (idempotent — skipped if already applied to this checkout).
# Adds --osd-keepaspect: lets subtitle/OSD positioning be computed as if
# aspect-correct letterboxing were on, independent of --keepaspect, which our
# 3D pipeline must keep off so the packed SBS/TAB frame fills the render
# buffer edge-to-edge (see LeiaSurfaceView/applyLeiaDisplayProperties).
if ! grep -q osd_keepaspect options/options.h 2>/dev/null; then
	git apply ../../patches/mpv-osd-keepaspect.patch
fi

if ! grep -q image-subs-scale options/options.c 2>/dev/null; then
    git apply ../../patches/mpv-image-subs-scale.patch
fi


unset CC CXX # meson wants these unset

meson setup $build --cross-file "$prefix_dir"/crossfile.txt \
	--default-library shared \
	-Diconv=disabled -Dlua=enabled \
	-Dlibmpv=true -Dcplayer=false \
	-Dmanpage-build=disabled

ninja -C $build -j$cores
if [ -f $build/libmpv.a ]; then
	echo >&2 "Meson fucked up, forcing rebuild."
	$0 clean
	exec $0 build
fi
DESTDIR="$prefix_dir" ninja -C $build install
