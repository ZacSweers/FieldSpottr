#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LIVE_DAYS="${LIVE_DAYS:-7}"
OUTPUT_ROOT="${OUTPUT_ROOT:-.}"
HRP_DIR="build/hrp"
NYC_LIVE_DIR="build/nyc-live"
USER_AGENT="${USER_AGENT:-Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36}"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required. Install it with: brew install jq" >&2
  exit 1
fi

find_chrome() {
  if [[ -n "${CHROME:-}" ]]; then
    printf '%s\n' "$CHROME"
    return
  fi

  if [[ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
    printf '%s\n' "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    return
  fi

  command -v google-chrome || command -v chromium || command -v chromium-browser || return 1
}

date_plus_days() {
  local start="$1"
  local offset="$2"

  if date -d "$start + $offset days" +%F >/dev/null 2>&1; then
    date -d "$start + $offset days" +%F
  else
    date -j -f "%Y-%m-%d" -v+"${offset}"d "$start" +%F
  fi
}

if ! CHROME="$(find_chrome)"; then
  echo "Chrome or Chromium was not found. Set CHROME=/path/to/chrome and retry." >&2
  exit 1
fi

mkdir -p "$HRP_DIR" "$NYC_LIVE_DIR"

echo "Dumping Hudson River Park field schedule"
"$CHROME" \
  --headless=new \
  --disable-gpu \
  --disable-software-rasterizer \
  --log-level=3 \
  --dump-dom \
  https://hudsonriverpark.org/visit/events/permits/fields/ \
  > "$HRP_DIR/fields.html" || rm -f "$HRP_DIR/fields.html"

TODAY="$(TZ=America/New_York date +%F)"

echo "Dumping NYC Parks live responses"
jq -r '.. | objects | .apiLocationId? // empty' areas.json | sort -u |
  while read -r api_location_id; do
    mkdir -p "$NYC_LIVE_DIR/$api_location_id"
    offset=0
    while [[ "$offset" -lt "$LIVE_DAYS" ]]; do
      live_date="$(date_plus_days "$TODAY" "$offset")"
      output="$NYC_LIVE_DIR/$api_location_id/$live_date.json"
      url="https://www.nycgovparks.org/api/athletic-fields?location=$api_location_id&date=$live_date"

      echo "  $api_location_id $live_date"
      if ! "$CHROME" \
          --headless=new \
          --disable-gpu \
          --disable-software-rasterizer \
          --log-level=3 \
          --user-agent="$USER_AGENT" \
          --dump-dom \
          "$url" \
          > "$output"; then
        echo "Chrome failed to dump $url" >&2
        rm -f "$output"
      elif [[ ! -s "$output" ]]; then
        echo "Chrome wrote an empty response for $url" >&2
        rm -f "$output"
      fi

      offset=$((offset + 7))
    done
  done

ARGS="--output=$OUTPUT_ROOT --live-days=$LIVE_DAYS --nyc-live-source-dir=$NYC_LIVE_DIR"
if [[ -s "$HRP_DIR/fields.html" ]]; then
  ARGS="$ARGS --hrp-source-file=$HRP_DIR/fields.html"
fi

./gradlew :generator:run --args="$ARGS"
