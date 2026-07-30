#!/usr/bin/env bash

# Shared Kernel browser transport. Callers are responsible for `set -euo pipefail`.

KERNEL_CLI="${KERNEL_CLI:-kernel}"
readonly KERNEL_MIN_VERSION="0.26.0"
KERNEL_SESSION_ID="${KERNEL_SESSION_ID:-}"

kernel_semver_at_least() {
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

response_is_cloudflare_challenge() {
  local file="$1"

  LC_ALL=C grep -aEiq \
    'Just a moment|Verify you are human|Attention Required|Cloudflare Ray ID|Sorry, you have been blocked' \
    "$file"
}

kernel_file_looks_like_html() {
  local file="$1"

  LC_ALL=C head -c 4096 "$file" |
    grep -aEiq '^[[:space:]]*(<!doctype[[:space:]]+html|<html|<head|<body|<title)'
}

kernel_require_configuration() {
  if [[ -z "${KERNEL_API_KEY:-}" ]]; then
    echo "KERNEL_API_KEY is required for Kernel browser requests." >&2
    return 1
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required. Install it with: brew install jq" >&2
    return 1
  fi
  if ! command -v "$KERNEL_CLI" >/dev/null 2>&1; then
    echo "Kernel CLI $KERNEL_MIN_VERSION or newer is required." >&2
    return 1
  fi

  local kernel_version
  kernel_version="$("$KERNEL_CLI" --version | awk 'NR == 1 { print $2 }')"
  if ! kernel_semver_at_least "$kernel_version" "$KERNEL_MIN_VERSION"; then
    echo "Kernel CLI $KERNEL_MIN_VERSION or newer is required; found ${kernel_version:-unknown}." >&2
    return 1
  fi
}

kernel_delete_session() {
  if [[ -z "$KERNEL_SESSION_ID" ]]; then
    return 0
  fi

  local session_id="$KERNEL_SESSION_ID"
  if "$KERNEL_CLI" browsers delete "$session_id" >/dev/null 2>&1; then
    KERNEL_SESSION_ID=""
    return 0
  fi

  echo "Warning: Kernel session cleanup failed; the server timeout will clean it up." >&2
  return 1
}

kernel_exit_trap() {
  local exit_status=$?
  trap - EXIT HUP INT TERM
  kernel_delete_session || true
  exit "$exit_status"
}

kernel_install_exit_traps() {
  trap kernel_exit_trap EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

kernel_start_session() {
  if [[ -n "$KERNEL_SESSION_ID" ]]; then
    echo "A Kernel browser session is already active." >&2
    return 1
  fi

  kernel_require_configuration || return 1
  kernel_install_exit_traps

  local session_json
  if ! session_json="$(
    "$KERNEL_CLI" browsers create --stealth --timeout 300 --output json --no-color
  )"; then
    echo "Failed to create a Kernel browser session." >&2
    return 1
  fi
  if ! KERNEL_SESSION_ID="$(
    printf '%s' "$session_json" | jq -er '.session_id | strings | select(length > 0)'
  )"; then
    echo "Kernel browser creation returned no session ID." >&2
    return 1
  fi

  echo "Using one Kernel stealth browser session for this refresh"
}

kernel_stop_session() {
  kernel_delete_session
}

kernel_navigate_for_challenge() {
  local url="$1"
  local quoted_url
  local code

  quoted_url="$(jq -Rn --arg url "$url" '$url')"
  code="const target = $quoted_url; await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 60000 }); await page.waitForFunction(() => { const text = document.title + ' ' + (document.body?.innerText ?? ''); return !/(Just a moment|Verify you are human|Attention Required|cf-chl|Cloudflare Ray ID)/i.test(text); }, undefined, { timeout: 60000 }).catch(() => {}); await page.waitForTimeout(2000);"
  "$KERNEL_CLI" browsers playwright execute "$KERNEL_SESSION_ID" "$code" --timeout 90 >/dev/null
}

kernel_candidate_needs_challenge_retry() {
  local file="$1"
  local mode="$2"

  [[ -s "$file" ]] || return 0
  if [[ "$mode" == "binary" ]] && ! kernel_file_looks_like_html "$file"; then
    return 1
  fi
  response_is_cloudflare_challenge "$file"
}

kernel_browser_fetch() {
  local url="$1"
  local output="$2"
  local mode="${3:-text}"
  local candidate="${output}.kernel.$$"
  local should_retry=false

  if [[ "$mode" != "text" && "$mode" != "binary" ]]; then
    echo "Kernel fetch mode must be text or binary; found: $mode" >&2
    return 1
  fi
  if [[ -z "$KERNEL_SESSION_ID" ]]; then
    echo "No Kernel browser session is active." >&2
    return 1
  fi

  rm -f "$output" "$candidate"
  if ! "$KERNEL_CLI" browsers curl "$KERNEL_SESSION_ID" "$url" \
    --fail \
    --silent \
    --max-time 45 \
    --output "$candidate"; then
    should_retry=true
  elif kernel_candidate_needs_challenge_retry "$candidate" "$mode"; then
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
  if kernel_candidate_needs_challenge_retry "$candidate" "$mode"; then
    echo "Kernel Browser Curl was still blocked after browser navigation for $url" >&2
    rm -f "$candidate"
    return 1
  fi

  mv -f "$candidate" "$output"
}

# Kept as a compatibility wrapper for the daily updater and its tests.
kernel_browser_curl() {
  kernel_browser_fetch "$1" "$2" text
}
