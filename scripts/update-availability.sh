#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIVE_DAYS="${LIVE_DAYS:-7}"
OUTPUT_ROOT="${OUTPUT_ROOT:-.}"
FETCH_BACKEND="${FETCH_BACKEND:-chrome}"
REQUIRE_FRESH_LIVE_SOURCES="${REQUIRE_FRESH_LIVE_SOURCES:-false}"
HRP_DIR="build/hrp"
NYC_CSV_DIR="${NYC_CSV_SOURCE_DIR:-build/nyc-csv}"
NYC_LIVE_DIR="build/nyc-live"
HRP_SOURCE_FILE="${HRP_SOURCE_FILE:-}"
NYC_CLOSURES_SOURCE_FILE="${NYC_CLOSURES_SOURCE_FILE:-}"
USER_AGENT="${USER_AGENT:-Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36}"
HRP_URL="https://hudsonriverpark.org/visit/events/permits/fields/"
HRP_READER_URL="https://r.jina.ai/http://r.jina.ai/http://$HRP_URL"
KERNEL_CLI="${KERNEL_CLI:-kernel}"
KERNEL_MIN_VERSION="0.26.0"
KERNEL_SESSION_ID=""
CHROME="${CHROME:-}"

find_chrome() {
  if [[ -n "$CHROME" ]]; then
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

response_is_cloudflare_challenge() {
  local file="$1"

  grep -Eiq \
    'Just a moment|Verify you are human|Attention Required|cf-chl-|challenge-platform|Cloudflare Ray ID|Sorry, you have been blocked' \
    "$file"
}

discard_invalid_json_dump() {
  local file="$1"
  local url="$2"
  local fetcher="$3"

  if [[ -s "$file" ]] && json_dump_is_usable "$file"; then
    return 0
  fi

  if [[ -s "$file" ]]; then
    local preview
    preview="$(sed -n '/[^[:space:]]/{s/^[[:space:]]*//;s/[[:space:]]*$//;p;q;}' "$file" | cut -c1-120)"
    echo "$fetcher wrote a non-JSON response for $url: $preview" >&2
  else
    echo "$fetcher wrote an empty response for $url" >&2
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

semver_at_least() {
  local current="$1"
  local required="$2"
  local current_major current_minor current_patch
  local required_major required_minor required_patch

  [[ "$current" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+) ]] || return 1
  current_major=$((10#${BASH_REMATCH[1]}))
  current_minor=$((10#${BASH_REMATCH[2]}))
  current_patch=$((10#${BASH_REMATCH[3]}))

  [[ "$required" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+) ]] || return 1
  required_major=$((10#${BASH_REMATCH[1]}))
  required_minor=$((10#${BASH_REMATCH[2]}))
  required_patch=$((10#${BASH_REMATCH[3]}))

  if ((current_major != required_major)); then
    ((current_major > required_major))
  elif ((current_minor != required_minor)); then
    ((current_minor > required_minor))
  else
    ((current_patch >= required_patch))
  fi
}

kernel_exit_trap() {
  local exit_status=$?
  trap - EXIT HUP INT TERM

  if [[ -n "$KERNEL_SESSION_ID" ]]; then
    if ! "$KERNEL_CLI" browsers delete "$KERNEL_SESSION_ID" >/dev/null 2>&1; then
      echo "Warning: Kernel session cleanup failed; the server timeout will clean it up." >&2
    fi
  fi

  exit "$exit_status"
}

initialize_fetch_backend() {
  case "$FETCH_BACKEND" in
    chrome)
      if ! CHROME="$(find_chrome)"; then
        echo "Chrome or Chromium was not found. Set CHROME=/path/to/chrome and retry." >&2
        exit 1
      fi
      echo "Using Chrome: $CHROME"
      ;;
    kernel)
      if [[ -z "${KERNEL_API_KEY:-}" ]]; then
        echo "KERNEL_API_KEY is required when FETCH_BACKEND=kernel." >&2
        exit 1
      fi
      if ! command -v "$KERNEL_CLI" >/dev/null 2>&1; then
        echo "Kernel CLI $KERNEL_MIN_VERSION or newer is required." >&2
        exit 1
      fi

      local kernel_version
      kernel_version="$("$KERNEL_CLI" --version | awk 'NR == 1 { print $2 }')"
      if ! semver_at_least "$kernel_version" "$KERNEL_MIN_VERSION"; then
        echo "Kernel CLI $KERNEL_MIN_VERSION or newer is required; found ${kernel_version:-unknown}." >&2
        exit 1
      fi

      trap kernel_exit_trap EXIT
      trap 'exit 129' HUP
      trap 'exit 130' INT
      trap 'exit 143' TERM

      local session_json
      if ! session_json="$("$KERNEL_CLI" browsers create --stealth --timeout 300 --output json --no-color)"; then
        echo "Failed to create a Kernel browser session." >&2
        exit 1
      fi
      if ! KERNEL_SESSION_ID="$(printf '%s' "$session_json" | jq -er '.session_id | strings | select(length > 0)')"; then
        echo "Kernel browser creation returned no session ID." >&2
        exit 1
      fi
      session_json=""
      echo "Using one Kernel stealth browser session for this refresh"
      ;;
    *)
      echo "FETCH_BACKEND must be chrome or kernel; found: $FETCH_BACKEND" >&2
      exit 1
      ;;
  esac
}

kernel_navigate_for_challenge() {
  local url="$1"
  local quoted_url
  local code

  quoted_url="$(jq -Rn --arg url "$url" '$url')"
  code="const target = $quoted_url; await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 60000 }); await page.waitForFunction(() => { const text = document.title + ' ' + (document.body?.innerText ?? ''); return !/(Just a moment|Verify you are human|Attention Required|cf-chl|Cloudflare Ray ID)/i.test(text); }, undefined, { timeout: 60000 }).catch(() => {}); await page.waitForTimeout(2000);"
  "$KERNEL_CLI" browsers playwright execute "$KERNEL_SESSION_ID" "$code" --timeout 90 >/dev/null
}

kernel_browser_curl() {
  local url="$1"
  local output="$2"
  local candidate="${output}.kernel.$$"
  local should_retry=false

  rm -f "$output" "$candidate"
  if ! "$KERNEL_CLI" browsers curl "$KERNEL_SESSION_ID" "$url" \
    --fail \
    --silent \
    --max-time 45 \
    --output "$candidate"; then
    should_retry=true
  elif [[ ! -s "$candidate" ]] || response_is_cloudflare_challenge "$candidate"; then
    should_retry=true
  fi

  if [[ "$should_retry" == "true" ]]; then
    rm -f "$candidate"
    echo "  Retrying through the Kernel browser after challenge handling"
    if ! kernel_navigate_for_challenge "$url"; then
      echo "Kernel Playwright navigation failed for $url; retrying Browser Curl anyway." >&2
    fi
    if ! "$KERNEL_CLI" browsers curl "$KERNEL_SESSION_ID" "$url" \
      --fail \
      --silent \
      --max-time 45 \
      --output "$candidate"; then
      echo "Kernel Browser Curl failed after browser navigation for $url" >&2
      rm -f "$candidate"
      return 1
    fi
  fi

  if [[ ! -s "$candidate" ]]; then
    echo "Kernel Browser Curl wrote an empty response for $url" >&2
    rm -f "$candidate"
    return 1
  fi
  if response_is_cloudflare_challenge "$candidate"; then
    echo "Kernel Browser Curl was still blocked after browser navigation for $url" >&2
    rm -f "$candidate"
    return 1
  fi

  mv -f "$candidate" "$output"
}

hrp_source_is_parseable() {
  local source_file="$1"

  ./gradlew --quiet :generator:run \
    --args="--validate-hrp-source=$source_file" \
    >/dev/null 2>&1
}

require_staged_hrp_source_in_strict_mode() {
  if [[ "$REQUIRE_FRESH_LIVE_SOURCES" != "true" || -n "$HRP_SOURCE_FILE" ]]; then
    return 0
  fi
  if [[ -s "$HRP_DIR/fields.html" || -s "$HRP_DIR/fields.md" ]]; then
    return 0
  fi

  echo "Strict refresh requires a fresh Hudson River Park source after all fallbacks." >&2
  return 1
}

validate_configuration() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required. Install it with: brew install jq" >&2
    exit 1
  fi
  if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required." >&2
    exit 1
  fi
  if [[ ! "$LIVE_DAYS" =~ ^[0-9]+$ ]]; then
    echo "LIVE_DAYS must be a non-negative integer; found: $LIVE_DAYS" >&2
    exit 1
  fi
  if [[ "$REQUIRE_FRESH_LIVE_SOURCES" != "true" && "$REQUIRE_FRESH_LIVE_SOURCES" != "false" ]]; then
    echo "REQUIRE_FRESH_LIVE_SOURCES must be true or false." >&2
    exit 1
  fi

  if [[ -n "$HRP_SOURCE_FILE" && ! -s "$HRP_SOURCE_FILE" ]]; then
    echo "HRP_SOURCE_FILE does not exist or is empty: $HRP_SOURCE_FILE" >&2
    exit 1
  fi
  if [[ -n "$NYC_CLOSURES_SOURCE_FILE" ]]; then
    if [[ ! -s "$NYC_CLOSURES_SOURCE_FILE" ]]; then
      echo "NYC_CLOSURES_SOURCE_FILE does not exist or is empty: $NYC_CLOSURES_SOURCE_FILE" >&2
      exit 1
    fi
    if ! json_dump_is_usable "$NYC_CLOSURES_SOURCE_FILE"; then
      echo "NYC_CLOSURES_SOURCE_FILE is not valid JSON: $NYC_CLOSURES_SOURCE_FILE" >&2
      exit 1
    fi
  fi
}

fetch_hrp_source() {
  rm -f "$HRP_DIR/fields.html" "$HRP_DIR/fields.md"
  echo "Dumping Hudson River Park field schedule"

  if [[ "$FETCH_BACKEND" == "chrome" ]]; then
    if ! "$CHROME" \
      --headless=new \
      --disable-gpu \
      --disable-software-rasterizer \
      --log-level=3 \
      --dump-dom \
      "$HRP_URL" \
      >"$HRP_DIR/fields.html" 2>/dev/null; then
      echo "Chrome failed to dump $HRP_URL" >&2
      rm -f "$HRP_DIR/fields.html"
    fi
  elif ! kernel_browser_curl "$HRP_URL" "$HRP_DIR/fields.html"; then
    rm -f "$HRP_DIR/fields.html"
  fi

  if [[ -s "$HRP_DIR/fields.html" ]] &&
    response_is_cloudflare_challenge "$HRP_DIR/fields.html"; then
    rm -f "$HRP_DIR/fields.html"
  fi
  if [[ -s "$HRP_DIR/fields.html" ]] &&
    ! hrp_source_is_parseable "$HRP_DIR/fields.html"; then
    echo "Primary Hudson River Park response produced no schedule rows; using reader fallback." >&2
    rm -f "$HRP_DIR/fields.html"
  fi

  if [[ ! -s "$HRP_DIR/fields.html" ]]; then
    echo "Dumping Hudson River Park field schedule via reader fallback"
    curl -fsSL "$HRP_READER_URL" >"$HRP_DIR/fields.md" || rm -f "$HRP_DIR/fields.md"
  fi

  if [[ ! -s "$HRP_DIR/fields.html" && ! -s "$HRP_DIR/fields.md" ]]; then
    print_manual_html_dump_action \
      "Hudson River Park field schedule" \
      "$HRP_URL" \
      "HRP_SOURCE_FILE"
  fi
}

fetch_nyc_live_sources() {
  local today="$1"

  echo "Dumping NYC Parks live responses"
  jq -r '.. | objects | .apiLocationId? // empty' areas.json | sort -u |
    while read -r api_location_id; do
      mkdir -p "$NYC_LIVE_DIR/$api_location_id"
      local offset=0
      while [[ "$offset" -lt "$LIVE_DAYS" ]]; do
        local live_date
        local output
        local url
        live_date="$(date_plus_days "$today" "$offset")"
        output="$NYC_LIVE_DIR/$api_location_id/$live_date.json"
        url="https://www.nycgovparks.org/api/athletic-fields?location=$api_location_id&date=$live_date"

        echo "  $api_location_id $live_date"
        if [[ "$FETCH_BACKEND" == "chrome" ]]; then
          rm -f "$output"
          if ! "$CHROME" \
            --headless=new \
            --disable-gpu \
            --disable-software-rasterizer \
            --log-level=3 \
            --user-agent="$USER_AGENT" \
            --dump-dom \
            "$url" \
            >"$output" 2>/dev/null; then
            echo "Chrome failed to dump $url" >&2
            rm -f "$output"
          else
            discard_invalid_json_dump "$output" "$url" "Chrome" || true
          fi
        elif kernel_browser_curl "$url" "$output"; then
          discard_invalid_json_dump "$output" "$url" "Kernel Browser Curl" || true
        else
          rm -f "$output"
        fi

        offset=$((offset + 7))
      done
    done
}

run_generator() {
  local args="--output=$OUTPUT_ROOT --live-days=$LIVE_DAYS --nyc-csv-source-dir=$NYC_CSV_DIR --nyc-live-source-dir=$NYC_LIVE_DIR"
  if [[ -n "$HRP_SOURCE_FILE" ]]; then
    args="$args --hrp-source-file=$HRP_SOURCE_FILE"
  elif [[ -s "$HRP_DIR/fields.html" ]]; then
    args="$args --hrp-source-file=$HRP_DIR/fields.html"
  elif [[ -s "$HRP_DIR/fields.md" ]]; then
    args="$args --hrp-source-file=$HRP_DIR/fields.md"
  fi
  if [[ -n "$NYC_CLOSURES_SOURCE_FILE" ]]; then
    args="$args --closures-source-file=$NYC_CLOSURES_SOURCE_FILE"
  fi
  if [[ "$REQUIRE_FRESH_LIVE_SOURCES" == "true" ]]; then
    args="$args --require-fresh-live-sources"
  fi

  ./gradlew :generator:run --args="$args"
}

main() {
  cd "$ROOT"
  validate_configuration
  mkdir -p "$HRP_DIR" "$NYC_CSV_DIR" "$NYC_LIVE_DIR"

  if [[ -n "$HRP_SOURCE_FILE" ]]; then
    echo "Using Hudson River Park field schedule source: $HRP_SOURCE_FILE"
  fi
  if [[ -n "$NYC_CLOSURES_SOURCE_FILE" ]]; then
    echo "Using NYC Parks closures source: $NYC_CLOSURES_SOURCE_FILE"
  else
    echo "No NYC Parks closures source configured; preserving previous closure rows" >&2
    echo "Set NYC_CLOSURES_SOURCE_FILE=/path/to/closures.json if you have a current closure dump." >&2
  fi

  initialize_fetch_backend
  if [[ -z "$HRP_SOURCE_FILE" ]]; then
    fetch_hrp_source
  fi
  require_staged_hrp_source_in_strict_mode

  local today
  today="$(TZ=America/New_York date +%F)"
  fetch_nyc_live_sources "$today"
  run_generator
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
