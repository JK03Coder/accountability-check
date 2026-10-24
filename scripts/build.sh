#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :test-target:assembleDebug :app:assembleDebugAndroidTest --console=plain "$@"
