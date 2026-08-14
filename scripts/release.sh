#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="${GRADLE:-${ROOT_DIR}/gradlew}"
RELEASE_DIR="${RELEASE_DIR:-${ROOT_DIR}/release}"
APK_DIR="${ROOT_DIR}/app/build/outputs/apk/release"
BUNDLE_DIR="${ROOT_DIR}/app/build/outputs/bundle/release"
SIGNING_FILE="${ROOT_DIR}/keystore.properties"
EXPECTED_OUTPUTS=("armeabi-v7a" "arm64-v8a" "x86" "x86_64" "universal")

cd "${ROOT_DIR}"

has_env_signing() {
  [[ -n "${WHITEDNS_RELEASE_STORE_FILE:-}" ]] &&
    [[ -n "${WHITEDNS_RELEASE_STORE_PASSWORD:-}" ]] &&
    [[ -n "${WHITEDNS_RELEASE_KEY_ALIAS:-}" ]] &&
    [[ -n "${WHITEDNS_RELEASE_KEY_PASSWORD:-}" ]]
}

has_file_key() {
  local key="$1"
  [[ -f "${SIGNING_FILE}" ]] &&
    grep -Eq "^[[:space:]]*(${key}|release\\.${key})[[:space:]]*=" "${SIGNING_FILE}"
}

has_file_signing() {
  has_file_key "storeFile" &&
    has_file_key "storePassword" &&
    has_file_key "keyAlias" &&
    has_file_key "keyPassword"
}

find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return 0
  fi

  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "${sdk_root}" && -f "${ROOT_DIR}/local.properties" ]]; then
    sdk_root="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "${ROOT_DIR}/local.properties" | head -n 1)"
  fi

  local candidate=""
  if [[ -n "${sdk_root}" && -d "${sdk_root}/build-tools" ]]; then
    for tool in "${sdk_root}"/build-tools/*/apksigner; do
      [[ -x "${tool}" ]] && candidate="${tool}"
    done
  fi

  [[ -n "${candidate}" ]] && printf '%s\n' "${candidate}"
}

if ! has_env_signing && ! has_file_signing; then
  cat >&2 <<EOF
Release signing is not configured.

Use environment variables:
  export WHITEDNS_RELEASE_STORE_FILE=/absolute/path/to/whitedns-release.jks
  export WHITEDNS_RELEASE_STORE_PASSWORD=...
  export WHITEDNS_RELEASE_KEY_ALIAS=whitedns
  export WHITEDNS_RELEASE_KEY_PASSWORD=...

Or create keystore.properties from keystore.properties.example.
EOF
  exit 1
fi

"${ROOT_DIR}/scripts/build-flclash-core.sh"

version_name="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)"
version_code="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -n 1)"
if [[ -z "${version_name}" || -z "${version_code}" ]]; then
  echo "Unable to read versionName/versionCode from app/build.gradle.kts" >&2
  exit 1
fi

if [[ "${SKIP_TESTS:-0}" != "1" ]]; then
  "${GRADLE}" test
fi

"${GRADLE}" :app:assembleRelease :app:bundleRelease

if [[ ! -d "${APK_DIR}" ]]; then
  echo "Release APK directory not found: ${APK_DIR}" >&2
  exit 1
fi

mkdir -p "${RELEASE_DIR}"
rm -f "${RELEASE_DIR}"/whitedns-[vV]"${version_name}"-*.apk \
  "${RELEASE_DIR}"/whitedns-[vV]"${version_name}".aab \
  "${RELEASE_DIR}/SHA256SUMS"

copied=()
for apk in "${APK_DIR}"/*.apk; do
  [[ -f "${apk}" ]] || continue
  base="$(basename "${apk}")"
  output=""
  case "${base}" in
    *armeabi-v7a*) output="armeabi-v7a" ;;
    *arm64-v8a*) output="arm64-v8a" ;;
    *x86_64*) output="x86_64" ;;
    *x86*) output="x86" ;;
    *universal*) output="universal" ;;
  esac
  [[ -n "${output}" ]] || continue

  dest="${RELEASE_DIR}/whitedns-V${version_name}-${output}.apk"
  cp "${apk}" "${dest}"
  copied+=("${dest}")
done

missing=()
for expected in "${EXPECTED_OUTPUTS[@]}"; do
  found=0
  for apk in "${copied[@]}"; do
    [[ "$(basename "${apk}")" == *"-${expected}.apk" ]] && found=1
  done
  [[ "${found}" -eq 1 ]] || missing+=("${expected}")
done

if [[ "${#missing[@]}" -gt 0 ]]; then
  echo "Missing expected release APK(s): ${missing[*]}" >&2
  exit 1
fi

bundle="${BUNDLE_DIR}/app-release.aab"
if [[ ! -f "${bundle}" ]]; then
  echo "Release bundle not found: ${bundle}" >&2
  exit 1
fi
bundle_dest="${RELEASE_DIR}/whitedns-V${version_name}.aab"
cp "${bundle}" "${bundle_dest}"

apksigner_path="$(find_apksigner || true)"
if [[ -n "${apksigner_path}" ]]; then
  for apk in "${copied[@]}"; do
    "${apksigner_path}" verify --print-certs "${apk}" >/dev/null
  done
else
  echo "Warning: apksigner not found; skipped signature verification." >&2
fi

artifacts=("${copied[@]}" "${bundle_dest}")
for artifact in "${artifacts[@]}"; do
  (
    cd "${RELEASE_DIR}"
    shasum -a 256 "$(basename "${artifact}")"
  ) >> "${RELEASE_DIR}/SHA256SUMS"
done

printf 'Release v%s (%s) created:\n' "${version_name}" "${version_code}"
for artifact in "${artifacts[@]}"; do
  printf '  %s\n' "${artifact}"
done
printf '  %s\n' "${RELEASE_DIR}/SHA256SUMS"
