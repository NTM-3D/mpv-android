#!/bin/bash -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD="$DIR/.."
MPV_ANDROID="$DIR/../.."

. $BUILD/include/path.sh
. $BUILD/include/depinfo.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf $MPV_ANDROID/{app,.}/build $MPV_ANDROID/app/src/main/{libs,obj}
	exit 0
else
	exit 255
fi

nativeprefix () {
	if [ -f $BUILD/prefix/$1/lib/libmpv.so ]; then
		echo $BUILD/prefix/$1
	else
		echo >&2 "Warning: libmpv.so not found in native prefix for $1, support will be omitted"
	fi
}

prefix32=$(nativeprefix "armv7l")
prefix64=$(nativeprefix "arm64")
prefix_x64=$(nativeprefix "x86_64")
prefix_x86=$(nativeprefix "x86")

if [[ -z "$prefix32" && -z "$prefix64" && -z "$prefix_x64" && -z "$prefix_x86" ]]; then
	echo >&2 "Error: no mpv library detected."
	exit 255
fi

PREFIX32=$prefix32 PREFIX64=$prefix64 PREFIX_X64=$prefix_x64 PREFIX_X86=$prefix_x86 \
ndk-build -C app/src/main -j$cores

./gradlew assembleDefaultDebug assembleDefaultRelease

if [ -n "$ANDROID_SIGNING_KEY" ]; then
    # Inject password if env var is set
    PASS_ARG=""
    [ -n "$ANDROID_SIGNING_PASS" ] && PASS_ARG="--ks-pass pass:${ANDROID_SIGNING_PASS} --key-pass pass:${ANDROID_SIGNING_PASS}"

    cd "${MPV_ANDROID}/app/build/outputs/apk"
    apksigner=${ANDROID_HOME}/build-tools/${v_sdk_build_tools}/apksigner
    
    # Only loop over 'default' flavor
    for v in default; do
        pushd $v
        # Sign only the release APKs found in the directory
        for apk in release/*-unsigned.apk; do
            "$apksigner" sign --ks "${ANDROID_SIGNING_KEY}" $PASS_ARG \
                --in $apk --out ${apk/-unsigned/-signed}
        done
        popd
    done
fi