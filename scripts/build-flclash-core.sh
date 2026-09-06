#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_FLCLASH_DIR="${FLCLASH_DIR:-${ROOT_DIR}/FlClash}"
FLCLASH_REPOSITORY="https://github.com/chen08209/FlClash.git"
MIHOMO_REPOSITORY="https://github.com/MetaCubeX/mihomo.git"
FLCLASH_COMMIT="ac2f6b919ec1ad395b61b4bb1e714a39c750babe"
FLCLASH_PATCH="${ROOT_DIR}/scripts/patches/flclash-v1.19.30.patch"
MIHOMO_PATCH="${ROOT_DIR}/scripts/patches/mihomo-v1.19.30-flclash.patch"
MIHOMO_COMMIT="ac017cdd246ce8bd547653d927e7bf77d7ee73d5"
MIHOMO_VERSION="v1.19.30"
OUT_JNI_DIR="${ROOT_DIR}/app/src/main/jniLibs"
OUT_INCLUDE_DIR="${ROOT_DIR}/app/src/main/cpp/includes"
VERSION_FILE="${OUT_JNI_DIR}/.mihomo-version"
CORE_BUILD_ID="${FLCLASH_COMMIT}-${MIHOMO_COMMIT}-whitedns-awg3"
API_LEVEL="${ANDROID_API_LEVEL:-26}"
ABIS=("armeabi-v7a" "arm64-v8a" "x86" "x86_64")

outputs_exist() {
  local abi
  [[ -f "${VERSION_FILE}" ]] || return 1
  [[ "$(<"${VERSION_FILE}")" == "${CORE_BUILD_ID}" ]] || return 1
  for abi in "${ABIS[@]}"; do
    [[ -f "${OUT_JNI_DIR}/${abi}/libclash.so" ]] || return 1
    [[ -f "${OUT_INCLUDE_DIR}/${abi}/libclash.h" ]] || return 1
    [[ -f "${OUT_INCLUDE_DIR}/${abi}/bride.h" ]] || return 1
  done
}

if [[ "${FORCE_FLCLASH_CORE_BUILD:-0}" != "1" ]] && outputs_exist; then
  echo "FlClash core outputs already exist. Set FORCE_FLCLASH_CORE_BUILD=1 to rebuild."
  exit 0
fi

if [[ ! -d "${SOURCE_FLCLASH_DIR}/.git" ]]; then
  if [[ -e "${SOURCE_FLCLASH_DIR}" && -n "$(find "${SOURCE_FLCLASH_DIR}" -mindepth 1 -maxdepth 1 2>/dev/null)" ]]; then
    echo "FlClash path exists but is not a git checkout: ${SOURCE_FLCLASH_DIR}" >&2
    exit 1
  fi
  mkdir -p "${SOURCE_FLCLASH_DIR}"
  git -C "${SOURCE_FLCLASH_DIR}" init
  git -C "${SOURCE_FLCLASH_DIR}" remote add origin "${FLCLASH_REPOSITORY}"
  git -C "${SOURCE_FLCLASH_DIR}" fetch --depth 1 origin "${FLCLASH_COMMIT}"
  git -C "${SOURCE_FLCLASH_DIR}" checkout --detach "${FLCLASH_COMMIT}"
fi

if [[ "$(git -C "${SOURCE_FLCLASH_DIR}" rev-parse HEAD)" != "${FLCLASH_COMMIT}" ]]; then
  echo "FlClash checkout must be pinned to ${FLCLASH_COMMIT}: ${SOURCE_FLCLASH_DIR}" >&2
  exit 1
fi

if [[ -n "$(git -C "${SOURCE_FLCLASH_DIR}" status --porcelain --ignore-submodules=none)" ]]; then
  echo "FlClash checkout must be clean; the build uses its exact pinned source: ${SOURCE_FLCLASH_DIR}" >&2
  exit 1
fi

BUILD_TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${BUILD_TMP_DIR}"' EXIT
FLCLASH_DIR="${BUILD_TMP_DIR}/FlClash"
CORE_DIR="${FLCLASH_DIR}/core"
SUBMODULE_DIR="${CORE_DIR}/Clash.Meta"
SOURCE_SUBMODULE_DIR="${SOURCE_FLCLASH_DIR}/core/Clash.Meta"

git clone --no-hardlinks --no-checkout "${SOURCE_FLCLASH_DIR}" "${FLCLASH_DIR}"
git -C "${FLCLASH_DIR}" checkout --detach "${FLCLASH_COMMIT}"

if ! git -C "${FLCLASH_DIR}" apply --check "${FLCLASH_PATCH}"; then
  echo "Unable to apply the pinned FlClash v1.19.30 compatibility patch." >&2
  exit 1
fi
git -C "${FLCLASH_DIR}" apply "${FLCLASH_PATCH}"

rm -rf "${SUBMODULE_DIR}"
if git -C "${SOURCE_SUBMODULE_DIR}" rev-parse --git-dir >/dev/null 2>&1 &&
  git -C "${SOURCE_SUBMODULE_DIR}" cat-file -e "${MIHOMO_COMMIT}^{commit}" 2>/dev/null; then
  git clone --no-hardlinks --no-checkout "${SOURCE_SUBMODULE_DIR}" "${SUBMODULE_DIR}"
  git -C "${SUBMODULE_DIR}" checkout --detach "${MIHOMO_COMMIT}"
else
  echo "Fetching pinned Mihomo ${MIHOMO_VERSION} source..."
  mkdir -p "${SUBMODULE_DIR}"
  git -C "${SUBMODULE_DIR}" init
  git -C "${SUBMODULE_DIR}" remote add origin "${MIHOMO_REPOSITORY}"
  git -C "${SUBMODULE_DIR}" fetch --depth 1 origin "${MIHOMO_COMMIT}"
  git -C "${SUBMODULE_DIR}" checkout --detach "${MIHOMO_COMMIT}"
fi

if [[ "$(git -C "${SUBMODULE_DIR}" rev-parse HEAD)" != "${MIHOMO_COMMIT}" ]]; then
  echo "FlClash Mihomo checkout must be pinned to ${MIHOMO_COMMIT}: ${SUBMODULE_DIR}" >&2
  exit 1
fi

if ! git -C "${SUBMODULE_DIR}" apply --check "${MIHOMO_PATCH}"; then
  echo "Unable to apply the pinned Mihomo AWG3 and FlClash compatibility patch." >&2
  exit 1
fi
git -C "${SUBMODULE_DIR}" apply "${MIHOMO_PATCH}"

validate_amnezia_v3_support() {
  local wireguard_source="${SUBMODULE_DIR}/adapter/outbound/wireguard.go"
  local marker
  local markers=(
    'Version int `proxy:"version,omitempty"`'
    'HeaderProtectionKey'
    'ContentPaddingAddition'
    'RekeyAfterTime'
    'RekeyTimeout'
    'RejectAfterTime'
    'KeepaliveTimeout'
    'MaxHandshakeAttempts'
    'RandomTrailers'
    'DisableCookies'
    'amneziav3.NewDevice'
    'header_protection_key='
    'content_padding_addition='
    'rekey_after_time='
    'rekey_timeout='
    'reject_after_time='
    'keepalive_timeout='
    'max_handshake_attempts='
    'random_trailers=1'
    'disable_cookies=1'
  )
  for marker in "${markers[@]}"; do
    if ! grep -Fq "${marker}" "${wireguard_source}"; then
      echo "Pinned Mihomo core lacks AmneziaWG v3 support marker: ${marker}" >&2
      exit 1
    fi
  done
}

validate_amnezia_v3_support

if ! command -v go >/dev/null 2>&1; then
  echo "Go is required to build the FlClash Mihomo core." >&2
  exit 1
fi

read_local_property() {
  local key="$1"
  [[ -f "${ROOT_DIR}/local.properties" ]] || return 0
  sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "${ROOT_DIR}/local.properties" | head -n 1
}

find_ndk_root() {
  if [[ -n "${ANDROID_NDK:-}" && -d "${ANDROID_NDK}" ]]; then
    printf '%s\n' "${ANDROID_NDK}"
    return 0
  fi
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    printf '%s\n' "${ANDROID_NDK_HOME}"
    return 0
  fi

  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "${sdk_root}" ]]; then
    sdk_root="$(read_local_property "sdk\\.dir")"
  fi

  if [[ -n "${sdk_root}" && -d "${sdk_root}/ndk" ]]; then
    find "${sdk_root}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1
    return 0
  fi
  if [[ -n "${sdk_root}" && -d "${sdk_root}/ndk-bundle" ]]; then
    printf '%s\n' "${sdk_root}/ndk-bundle"
    return 0
  fi
}

NDK_ROOT="$(find_ndk_root || true)"
if [[ -z "${NDK_ROOT}" || ! -d "${NDK_ROOT}" ]]; then
  echo "Android NDK not found. Install the NDK or set ANDROID_NDK/ANDROID_NDK_HOME." >&2
  exit 1
fi

TOOLCHAIN_BIN="$(find "${NDK_ROOT}/toolchains/llvm/prebuilt" -mindepth 2 -maxdepth 2 -type d -name bin | head -n 1)"
if [[ -z "${TOOLCHAIN_BIN}" || ! -d "${TOOLCHAIN_BIN}" ]]; then
  echo "Android NDK LLVM toolchain not found under ${NDK_ROOT}." >&2
  exit 1
fi

build_abi() {
  local abi="$1"
  local goarch=""
  local goarm=""
  local cc_name=""

  case "${abi}" in
    armeabi-v7a)
      goarch="arm"
      goarm="7"
      cc_name="armv7a-linux-androideabi${API_LEVEL}-clang"
      ;;
    arm64-v8a)
      goarch="arm64"
      cc_name="aarch64-linux-android${API_LEVEL}-clang"
      ;;
    x86)
      goarch="386"
      cc_name="i686-linux-android${API_LEVEL}-clang"
      ;;
    x86_64)
      goarch="amd64"
      cc_name="x86_64-linux-android${API_LEVEL}-clang"
      ;;
    *)
      echo "Unsupported ABI: ${abi}" >&2
      return 1
      ;;
  esac

  local cc="${TOOLCHAIN_BIN}/${cc_name}"
  if [[ ! -x "${cc}" ]]; then
    echo "Missing NDK compiler: ${cc}" >&2
    return 1
  fi

  local tmp_out="${BUILD_TMP_DIR}/outputs/${abi}"
  local jni_out="${OUT_JNI_DIR}/${abi}"
  local include_out="${OUT_INCLUDE_DIR}/${abi}"
  mkdir -p "${tmp_out}" "${jni_out}" "${include_out}"

  echo "Building FlClash Mihomo core for ${abi}..."
  (
    cd "${CORE_DIR}"
    env \
      GOOS=android \
      GOARCH="${goarch}" \
      ${goarm:+GOARM="${goarm}"} \
      GOCACHE="${BUILD_TMP_DIR}/go-build-cache" \
      CGO_ENABLED=1 \
      CC="${cc}" \
      CFLAGS="-O3 -Werror" \
      go build \
        -ldflags="-X github.com/metacubex/mihomo/constant.Version=${MIHOMO_VERSION} -w -s" \
        -tags=with_gvisor \
        -buildmode=c-shared \
        -o "${tmp_out}/libclash.so" \
        .
  )

  install -m 0644 "${tmp_out}/libclash.so" "${jni_out}/libclash.so"
  install -m 0644 "${tmp_out}/libclash.h" "${include_out}/libclash.h"
  install -m 0644 "${CORE_DIR}/bride.h" "${include_out}/bride.h"
}

for abi in "${ABIS[@]}"; do
  build_abi "${abi}"
done

printf '%s\n' "${CORE_BUILD_ID}" > "${VERSION_FILE}"
echo "FlClash Mihomo core generated under app/src/main/jniLibs."
