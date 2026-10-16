#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
if ! command -v unzip >/dev/null || ! command -v curl >/dev/null || ! command -v python3 >/dev/null; then
  apt-get update -qq
  apt-get install -y --no-install-recommends unzip curl python3 ca-certificates
fi
# Pin the official tools archive and verify it before extraction.
archive_id=16111833
if [[ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  archive=$(mktemp)
  staging=$(mktemp -d)
  trap 'rm -f "$archive"; rm -rf "$staging"' EXIT
  curl --fail --location --retry 3 "https://dl.google.com/android/repository/commandlinetools-linux-${archive_id}_latest.zip" -o "$archive"
  printf '%s  %s\n' e025545c62a8e64c7559119566a569fb1dec5f60 "$archive" | sha1sum --check
  unzip -q "$archive" -d "$staging"
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  mv "$staging/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
fi
# yes exits with SIGPIPE when sdkmanager finishes; check sdkmanager's exit status.
set +o pipefail
yes | sdkmanager --licenses >/dev/null
license_status=${PIPESTATUS[1]}
set -o pipefail
[[ "$license_status" == 0 ]]
sdkmanager 'platform-tools' 'platforms;android-37.2' 'build-tools;36.0.0'
