#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLCLASH_DIR="${FLCLASH_DIR:-${ROOT_DIR}/FlClash}"
CORE_DIR="${FLCLASH_DIR}/core"
SUBMODULE_DIR="${CORE_DIR}/Clash.Meta"
FLCLASH_REPOSITORY="https://github.com/chen08209/FlClash.git"
FLCLASH_COMMIT="ac2f6b919ec1ad395b61b4bb1e714a39c750babe"
FLCLASH_PATCH="${ROOT_DIR}/scripts/patches/flclash-v1.19.29.patch"
OUT_JNI_DIR="${ROOT_DIR}/app/src/main/jniLibs"
OUT_INCLUDE_DIR="${ROOT_DIR}/app/src/main/cpp/includes"
VERSION_FILE="${OUT_JNI_DIR}/.mihomo-version"
MIHOMO_TAG="v1.19.29"
FLCLASH_PATCH_COMMIT="80362fc1895dcf60b79b562896653046e0687413"
API_LEVEL="${ANDROID_API_LEVEL:-26}"
ABIS=("armeabi-v7a" "arm64-v8a" "x86" "x86_64")

if [[ ! -d "${FLCLASH_DIR}/.git" ]]; then
  if [[ -e "${FLCLASH_DIR}" && -n "$(find "${FLCLASH_DIR}" -mindepth 1 -maxdepth 1 2>/dev/null)" ]]; then
    echo "FlClash path exists but is not a git checkout: ${FLCLASH_DIR}" >&2
    exit 1
  fi
  mkdir -p "${FLCLASH_DIR}"
  git -C "${FLCLASH_DIR}" init
  git -C "${FLCLASH_DIR}" remote add origin "${FLCLASH_REPOSITORY}"
  git -C "${FLCLASH_DIR}" fetch --depth 1 origin "${FLCLASH_COMMIT}"
  git -C "${FLCLASH_DIR}" checkout --detach "${FLCLASH_COMMIT}"
fi

if [[ "$(git -C "${FLCLASH_DIR}" rev-parse HEAD)" != "${FLCLASH_COMMIT}" ]]; then
  echo "FlClash checkout must be pinned to ${FLCLASH_COMMIT}: ${FLCLASH_DIR}" >&2
  exit 1
fi

if git -C "${FLCLASH_DIR}" apply --unidiff-zero --reverse --check "${FLCLASH_PATCH}" 2>/dev/null; then
  :
elif git -C "${FLCLASH_DIR}" apply --unidiff-zero --check "${FLCLASH_PATCH}"; then
  git -C "${FLCLASH_DIR}" apply --unidiff-zero "${FLCLASH_PATCH}"
else
  echo "Unable to apply the pinned FlClash compatibility patch: ${FLCLASH_PATCH}" >&2
  exit 1
fi

if [[ ! -f "${SUBMODULE_DIR}/go.mod" ]]; then
  if [[ -e "${SUBMODULE_DIR}" && -n "$(find "${SUBMODULE_DIR}" -mindepth 1 -maxdepth 1 2>/dev/null)" ]]; then
    echo "FlClash Clash.Meta submodule is present but incomplete: ${SUBMODULE_DIR}" >&2
    exit 1
  fi
  echo "Fetching Mihomo ${MIHOMO_TAG} with FlClash compatibility..."
  rm -rf "${SUBMODULE_DIR}"
  git clone --depth 1 --branch "${MIHOMO_TAG}" \
    https://github.com/MetaCubeX/mihomo.git \
    "${SUBMODULE_DIR}"
  git -C "${SUBMODULE_DIR}" fetch --depth 2 \
    https://github.com/chen08209/Clash.Meta.git "${FLCLASH_PATCH_COMMIT}"
  git -c commit.gpgsign=false \
    -c user.name="WhiteVPN Build" \
    -c user.email="actions@users.noreply.github.com" \
    -C "${SUBMODULE_DIR}" cherry-pick "${FLCLASH_PATCH_COMMIT}"
fi

if ! git -C "${SUBMODULE_DIR}" rev-parse --verify "${MIHOMO_TAG}^{commit}" >/dev/null 2>&1 ||
  ! git -C "${SUBMODULE_DIR}" merge-base --is-ancestor "${MIHOMO_TAG}" HEAD; then
  echo "FlClash Mihomo source is not based on ${MIHOMO_TAG}: ${SUBMODULE_DIR}" >&2
  exit 1
fi

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

outputs_exist() {
  local abi
  [[ -f "${VERSION_FILE}" ]] || return 1
  [[ "$(<"${VERSION_FILE}")" == "${MIHOMO_TAG}" ]] || return 1
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

(cd "${CORE_DIR}" && go mod tidy)

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

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

  local tmp_out="${TMP_DIR}/${abi}"
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
      CGO_ENABLED=1 \
      CC="${cc}" \
      CFLAGS="-O3 -Werror" \
      go build \
        -ldflags="-X github.com/metacubex/mihomo/constant.Version=${MIHOMO_TAG} -w -s" \
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

printf '%s\n' "${MIHOMO_TAG}" > "${VERSION_FILE}"
echo "FlClash Mihomo core generated under app/src/main/jniLibs."
