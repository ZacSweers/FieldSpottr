#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LIVE_DAYS="${LIVE_DAYS:-7}"
OUTPUT_ROOT="${OUTPUT_ROOT:-.}"
HRP_DIR="build/hrp"
NYC_CSV_DIR="${NYC_CSV_SOURCE_DIR:-build/nyc-csv}"
NYC_LIVE_DIR="build/nyc-live"
HRP_SOURCE_FILE="${HRP_SOURCE_FILE:-}"
NYC_CLOSURES_SOURCE_FILE="${NYC_CLOSURES_SOURCE_FILE:-}"
USER_AGENT="${USER_AGENT:-Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36}"
HRP_URL="https://hudsonriverpark.org/visit/events/permits/fields/"
HRP_READER_URL="https://r.jina.ai/http://r.jina.ai/http://$HRP_URL"

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

json_dump_is_usable() {
  local file="$1"

  jq empty "$file" >/dev/null 2>&1 || grep -Eiq '<pre[^>]*>[[:space:]]*[\{\[]' "$file"
}

discard_invalid_json_dump() {
  local file="$1"
  local url="$2"

  if [[ -s "$file" ]] && json_dump_is_usable "$file"; then
    return 0
  fi

  if [[ -s "$file" ]]; then
    local preview
    preview="$(sed -n '/[^[:space:]]/{s/^[[:space:]]*//;s/[[:space:]]*$//;p;q;}' "$file" | cut -c1-120)"
    echo "Chrome wrote a non-JSON response for $url: $preview" >&2
  else
    echo "Chrome wrote an empty response for $url" >&2
  fi
  rm -f "$file"
  return 1
}

print_manual_html_dump_action() {
  local label="$1"
  local url="$2"
  local env_name="$3"

  cat >&2 <<EOF
$label could not be dumped automatically. The generator will preserve previous rows for that source.
Manual refresh action:
  1. Open $url in normal Chrome.
  2. Copy the full page HTML from DevTools Console:
     copy(document.documentElement.outerHTML)
  3. Save it to a local file.
  4. Rerun with $env_name=/path/to/saved.html scripts/update-availability.sh
EOF
}

if ! CHROME="$(find_chrome)"; then
  echo "Chrome or Chromium was not found. Set CHROME=/path/to/chrome and retry." >&2
  exit 1
fi
echo "Using Chrome: $CHROME"

mkdir -p "$HRP_DIR" "$NYC_CSV_DIR" "$NYC_LIVE_DIR"

if [[ -n "$HRP_SOURCE_FILE" ]]; then
  if [[ ! -s "$HRP_SOURCE_FILE" ]]; then
    echo "HRP_SOURCE_FILE does not exist or is empty: $HRP_SOURCE_FILE" >&2
    exit 1
  fi
  echo "Using Hudson River Park field schedule source: $HRP_SOURCE_FILE"
else
  echo "Dumping Hudson River Park field schedule"
  "$CHROME" \
    --headless=new \
    --disable-gpu \
    --disable-software-rasterizer \
    --log-level=3 \
    --dump-dom \
    "$HRP_URL" \
    > "$HRP_DIR/fields.html" 2>/dev/null || rm -f "$HRP_DIR/fields.html"

  if [[ -s "$HRP_DIR/fields.html" ]] && grep -qi "Cloudflare" "$HRP_DIR/fields.html"; then
    rm -f "$HRP_DIR/fields.html"
  fi

  if [[ ! -s "$HRP_DIR/fields.html" ]]; then
    echo "Dumping Hudson River Park field schedule via reader fallback"
    curl -fsSL "$HRP_READER_URL" > "$HRP_DIR/fields.md" || rm -f "$HRP_DIR/fields.md"
  fi

  if [[ ! -s "$HRP_DIR/fields.html" && ! -s "$HRP_DIR/fields.md" ]]; then
    print_manual_html_dump_action "Hudson River Park field schedule" "$HRP_URL" "HRP_SOURCE_FILE"
  fi
fi

TODAY="$(TZ=America/New_York date +%F)"

if [[ -n "$NYC_CLOSURES_SOURCE_FILE" ]]; then
  if [[ ! -s "$NYC_CLOSURES_SOURCE_FILE" ]]; then
    echo "NYC_CLOSURES_SOURCE_FILE does not exist or is empty: $NYC_CLOSURES_SOURCE_FILE" >&2
    exit 1
  fi
  if ! json_dump_is_usable "$NYC_CLOSURES_SOURCE_FILE"; then
    echo "NYC_CLOSURES_SOURCE_FILE is not valid JSON: $NYC_CLOSURES_SOURCE_FILE" >&2
    exit 1
  fi
  echo "Using NYC Parks closures source: $NYC_CLOSURES_SOURCE_FILE"
else
  echo "No NYC Parks closures source configured; preserving previous closure rows" >&2
  echo "Set NYC_CLOSURES_SOURCE_FILE=/path/to/closures.json if you have a current closure dump." >&2
fi

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
          > "$output" 2>/dev/null; then
        echo "Chrome failed to dump $url" >&2
        rm -f "$output"
      else
        discard_invalid_json_dump "$output" "$url" || true
      fi

      offset=$((offset + 7))
    done
  done

ARGS="--output=$OUTPUT_ROOT --live-days=$LIVE_DAYS --nyc-csv-source-dir=$NYC_CSV_DIR --nyc-live-source-dir=$NYC_LIVE_DIR"
if [[ -n "$HRP_SOURCE_FILE" ]]; then
  ARGS="$ARGS --hrp-source-file=$HRP_SOURCE_FILE"
elif [[ -s "$HRP_DIR/fields.html" ]]; then
  ARGS="$ARGS --hrp-source-file=$HRP_DIR/fields.html"
elif [[ -s "$HRP_DIR/fields.md" ]]; then
  ARGS="$ARGS --hrp-source-file=$HRP_DIR/fields.md"
fi
if [[ -n "$NYC_CLOSURES_SOURCE_FILE" ]]; then
  ARGS="$ARGS --closures-source-file=$NYC_CLOSURES_SOURCE_FILE"
fi

./gradlew :generator:run --args="$ARGS"
