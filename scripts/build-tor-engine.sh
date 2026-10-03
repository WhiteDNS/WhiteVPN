#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOR_VERSION="${TOR_VERSION:-0.4.9.13}"
OPENSSL_VERSION="${OPENSSL_VERSION:-3.6.5}"
LIBEVENT_VERSION="${LIBEVENT_VERSION:-2.1.12-stable}"
API=26
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
OUT="$ROOT/app/src/main/jniLibs"
WORK="$ROOT/build/engines/tor-work"
NDK="${NDK:?set NDK to an Android NDK}"
JOBS="${JOBS:-$(nproc)}"

case "$(uname -s)" in
  MINGW*|MSYS*) PREBUILT=windows-x86_64 ;;
  Linux*) PREBUILT=linux-x86_64 ;;
  *) echo "Unsupported build host" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$PREBUILT"
export PATH="$TOOLCHAIN/bin:$PATH"
export ANDROID_NDK_ROOT="$NDK"

mkdir -p "$WORK/src" "$OUT"
"${PYTHON:-python3}" "$ROOT/scripts/fetch-tor-input.py" openssl "$WORK/src/openssl.tar.gz"
"${PYTHON:-python3}" "$ROOT/scripts/fetch-tor-input.py" libevent "$WORK/src/libevent.tar.gz"
"${PYTHON:-python3}" "$ROOT/scripts/fetch-tor-input.py" tor "$WORK/src/tor.tar.gz"
for abi in $ABIS; do
  case "$abi" in
    arm64-v8a) ossl=android-arm64; host=aarch64-linux-android; cc=aarch64-linux-android$API-clang ;;
    armeabi-v7a) ossl=android-arm; host=arm-linux-androideabi; cc=armv7a-linux-androideabi$API-clang ;;
    x86_64) ossl=android-x86_64; host=x86_64-linux-android; cc=x86_64-linux-android$API-clang ;;
    *) echo "unknown ABI $abi" >&2; exit 1 ;;
  esac
  build="$(mktemp -d "$WORK/$abi-XXXXXX")"
  prefix="$build/prefix"
  mkdir -p "$prefix"
  if [[ "$PREBUILT" == windows-x86_64 ]]; then
    # Use the native clang driver directly; Android API wrappers are .cmd files on Windows.
    case "$abi" in
      armeabi-v7a) target=armv7a-linux-androideabi$API ;;
      *) target=${host}$API ;;
    esac
    CC="clang --target=$target"
  else CC="$cc"; fi
  export CC AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip

  tar -xzf "$WORK/src/openssl.tar.gz" -C "$build"
  (cd "$build/openssl-$OPENSSL_VERSION" &&
    ./Configure "$ossl" -D__ANDROID_API__="$API" no-shared no-tests no-docs no-apps no-module \
      --prefix="$prefix" --libdir=lib > "$build/openssl.log" 2>&1 &&
    make -j"$JOBS" build_libs >> "$build/openssl.log" 2>&1 &&
    make install_dev >> "$build/openssl.log" 2>&1) || { tail -40 "$build/openssl.log"; exit 1; }

  tar -xzf "$WORK/src/libevent.tar.gz" -C "$build"
  (cd "$build/libevent-$LIBEVENT_VERSION" &&
    ./configure --host="$host" --prefix="$prefix" --disable-shared --enable-static --disable-openssl \
      --disable-samples --disable-libevent-regress --disable-debug-mode > "$build/libevent.log" 2>&1 &&
    make -j"$JOBS" >> "$build/libevent.log" 2>&1 &&
    make install >> "$build/libevent.log" 2>&1) || { tail -40 "$build/libevent.log"; exit 1; }

  tar -xzf "$WORK/src/tor.tar.gz" -C "$build"
  (cd "$build/tor-$TOR_VERSION" &&
    : > "$build/tor.log" &&
    LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    ./configure --host="$host" \
      --enable-static-libevent --with-libevent-dir="$prefix" \
      --enable-static-openssl --with-openssl-dir="$prefix" \
      --disable-asciidoc --disable-manpage --disable-html-manual --disable-unittests \
      --disable-tool-name-check --disable-module-relay --disable-module-dirauth \
      --disable-lzma --disable-zstd --disable-seccomp --disable-libscrypt >> "$build/tor.log" 2>&1 &&
    make -j"$JOBS" src/app/tor >> "$build/tor.log" 2>&1) || { tail -60 "$build/tor.log"; exit 1; }

  mkdir -p "$OUT/$abi"
  cp "$build/tor-$TOR_VERSION/src/app/tor" "$OUT/$abi/libtor.so"
  llvm-strip "$OUT/$abi/libtor.so"
  ls -l "$OUT/$abi/libtor.so"
done
