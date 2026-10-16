#!/usr/bin/env bash
set -euo pipefail
apk=${1:?Usage: verify-signature.sh release.apk}
expected=d0b095e3ac0b129d08201b886db007e56d89fb8f4e5eac548bb59edfaeb2f333
sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK path is required}}
verification=$("$sdk_root/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$apk")
actual=$(printf '%s\n' "$verification" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
signer_count=$(printf '%s\n' "$verification" | grep -c '^Signer #[0-9]* certificate SHA-256 digest: ')
if [ "$signer_count" != 1 ] || [ "$actual" != "$expected" ]; then
  echo 'ERROR: release APK signer does not match the original BankNotify certificate' >&2
  exit 1
fi
printf 'Release APK signature verified: %s\n' "$actual"
