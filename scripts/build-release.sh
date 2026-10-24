#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

: "${ANDROID_HOME:?Run this script with mise exec -- ./scripts/build-release.sh}"
build_tools="$ANDROID_HOME/build-tools/35.0.0"
for tool in "$build_tools/zipalign" "$build_tools/apksigner"; do
  [[ -x "$tool" ]] || { echo "Required tool not found: $tool" >&2; exit 1; }
done

# Signing credentials stay outside the checkout, readable only by this user.
umask 077
signing_dir="${XDG_CONFIG_HOME:-$HOME}/.android/accountability-check-signing"
keystore="$signing_dir/release-key.p12"
password_file="$signing_dir/release-password"
if { [[ -e "$keystore" ]] && [[ ! -e "$password_file" ]]; } ||
   { [[ ! -e "$keystore" ]] && [[ -e "$password_file" ]]; }; then
  echo "Both the release key and its password file must exist. Inspect $signing_dir." >&2
  exit 1
fi
if [[ ! -e "$keystore" ]]; then
  mkdir -p "$signing_dir"
  openssl rand -base64 36 > "$password_file"
  if ! keytool -genkeypair -noprompt -keystore "$keystore" -storetype PKCS12 \
    -alias accountabilitycheck -keyalg RSA -keysize 4096 -validity 10000 \
    -dname 'CN=Accountability Check, O=JK03Coder, C=NZ' \
    -storepass:file "$password_file" -keypass:file "$password_file"; then
    rm -f -- "$keystore" "$password_file"
    exit 1
  fi
  echo "Created release signing credentials in $signing_dir. Back up both files securely."
fi

version="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Invalid release version: $version" >&2; exit 1; }
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease --console=plain

output_dir="$PWD/artifacts/release"
mkdir -p "$output_dir"
apk_name="AccountabilityCheck-v$version.apk"
release_apk="$output_dir/$apk_name"
aligned_apk="$(mktemp "$output_dir/.aligned-XXXXXX.apk")"
trap 'rm -f -- "$aligned_apk"' EXIT
"$build_tools/zipalign" -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$aligned_apk"
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias accountabilitycheck \
  --ks-pass "file:$password_file" \
  --out "$release_apk" "$aligned_apk"
"$build_tools/zipalign" -c -P 16 4 "$release_apk"
"$build_tools/apksigner" verify --verbose --print-certs "$release_apk"
(cd "$output_dir" && sha256sum "$apk_name" > "$apk_name.sha256")
echo "Release APK: $release_apk"
cat "$release_apk.sha256"
