#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export NG_BUILD_MAX_WORKERS="${NG_BUILD_MAX_WORKERS:-2}"

echo "Building Runtime Bay for development..."
bash scripts/build-native.sh
npm run build:web
mvn -q -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" -DskipTests package

export RUNTIMEBAY_JAR="$PWD/backend/target/runtimebay-backend.jar"
export RUNTIMEBAY_LAUNCHER="$PWD/.build/runtimebay-launcher"
export RUNTIMEBAY_WEB_ROOT="$PWD/dist/web/browser"
export RUNTIMEBAY_DEV_MODE=1

mkdir -p .build
npm run watch:web > .build/web-watch.log 2>&1 &
watch_pid=$!
fingerprint_web_assets() {
  find "$RUNTIMEBAY_WEB_ROOT" -type f ! -name index.html -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256 | awk '{print $1}'
}
monitor_pid=""
(
  previous="$(fingerprint_web_assets)"
  while kill -0 "$watch_pid" 2>/dev/null; do
    sleep 1
    current="$(fingerprint_web_assets)"
    if [[ "$current" != "$previous" ]]; then
      sleep 1
      stable="$(fingerprint_web_assets)"
      if [[ "$stable" == "$current" ]]; then
        touch "$RUNTIMEBAY_WEB_ROOT/index.html"
        previous="$stable"
      fi
    fi
  done
) &
monitor_pid=$!
stop_watcher() {
  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true
  kill "$watch_pid" 2>/dev/null || true
  wait "$watch_pid" 2>/dev/null || true
}
trap stop_watcher EXIT INT TERM

echo "Starting Runtime Bay. Frontend edits will rebuild and refresh automatically."
set +e
.build/RuntimeBay
app_status=$?
set -e
exit "$app_status"
