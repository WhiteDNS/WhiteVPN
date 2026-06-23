#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SING_BOX_VERSION="${SING_BOX_VERSION:-v1.13.13}"
WORK_DIR="${ROOT_DIR}/.third_party/sing-box-${SING_BOX_VERSION}"
LIB_DIR="${ROOT_DIR}/app/libs"

if [[ -z "${JAVA_HOME:-}" ]]; then
  DEFAULT_JAVA_HOME="/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home"
  if [[ -x "${DEFAULT_JAVA_HOME}/bin/java" ]]; then
    export JAVA_HOME="${DEFAULT_JAVA_HOME}"
  fi
fi

if ! "${JAVA_HOME:-}/bin/java" --version 2>/dev/null | grep -q "openjdk 17"; then
  echo "JDK 17 is required. Set JAVA_HOME to an OpenJDK 17 installation." >&2
  exit 1
fi

if ! command -v go >/dev/null 2>&1; then
  echo "Go is required to build libbox." >&2
  exit 1
fi

export ANDROID_HOME="${ANDROID_HOME:-/Users/pedrammarandi/Library/Android/sdk}"
export ANDROID_SDK_HOME="${ANDROID_SDK_HOME:-${ANDROID_HOME}}"

mkdir -p "${ROOT_DIR}/.third_party" "${LIB_DIR}"

if [[ ! -d "${WORK_DIR}/.git" ]]; then
  git clone --depth 1 --branch "${SING_BOX_VERSION}" https://github.com/SagerNet/sing-box.git "${WORK_DIR}"
fi

pushd "${WORK_DIR}" >/dev/null
make lib_install
make lib_android
cp libbox.aar "${LIB_DIR}/libbox.aar"
popd >/dev/null

echo "Created ${LIB_DIR}/libbox.aar"
