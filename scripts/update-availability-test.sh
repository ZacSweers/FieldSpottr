#!/usr/bin/env bash

set -euo pipefail

TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPDATER="$TEST_ROOT/scripts/update-availability.sh"
FAKE_KERNEL="$TEST_ROOT/scripts/testdata/fake-kernel.sh"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fieldspottr-update-test.XXXXXX")"

cleanup() {
  rm -rf "$TEST_DIR"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local pattern="$2"
  grep -Fq "$pattern" "$file" || fail "Expected $file to contain: $pattern"
}

reset_fake() {
  : >"$TEST_DIR/kernel.log"
  rm -f "$TEST_DIR/kernel.state"
}

run_updater_function() {
  local mode="$1"
  local output="$2"
  FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
    FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
    FAKE_KERNEL_MODE="$mode" \
    FETCH_BACKEND=kernel \
    KERNEL_API_KEY=test-key \
    KERNEL_CLI="$FAKE_KERNEL" \
    bash -c 'source "$1"; initialize_fetch_backend; kernel_browser_curl "https://example.com/data" "$2"' \
    _ \
    "$UPDATER" \
    "$output"
}

missing_key_output="$TEST_DIR/missing-key.txt"
if FETCH_BACKEND=kernel KERNEL_API_KEY= bash -c \
  'source "$1"; initialize_fetch_backend' _ "$UPDATER" >"$missing_key_output" 2>&1; then
  fail "Kernel mode accepted a missing API key"
fi
assert_contains "$missing_key_output" "KERNEL_API_KEY is required when FETCH_BACKEND=kernel."

strict_hrp_output="$TEST_DIR/strict-hrp.txt"
if bash -c \
  'source "$1"; REQUIRE_FRESH_LIVE_SOURCES=true; HRP_SOURCE_FILE=""; HRP_DIR="$2"; mkdir -p "$HRP_DIR"; require_staged_hrp_source_in_strict_mode' \
  _ \
  "$UPDATER" \
  "$TEST_DIR/missing-hrp" >"$strict_hrp_output" 2>&1; then
  fail "Strict mode accepted a missing staged Hudson River Park source"
fi
assert_contains \
  "$strict_hrp_output" \
  "Strict refresh requires a fresh Hudson River Park source after all fallbacks."

reset_fake
old_version_output="$TEST_DIR/old-version.txt"
if FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_VERSION=0.25.9 \
  FETCH_BACKEND=kernel \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  bash -c 'source "$1"; initialize_fetch_backend' _ "$UPDATER" >"$old_version_output" 2>&1; then
  fail "Kernel mode accepted an old CLI"
fi
assert_contains "$old_version_output" "Kernel CLI 0.26.0 or newer is required; found 0.25.9."

reset_fake
raw_output="$TEST_DIR/raw.json"
run_updater_function raw "$raw_output"
jq -e '.availability == {}' "$raw_output" >/dev/null
[[ "$(grep -c '^browsers create ' "$TEST_DIR/kernel.log")" -eq 1 ]] ||
  fail "Expected exactly one Kernel session"
[[ "$(grep -c '^browsers curl ' "$TEST_DIR/kernel.log")" -eq 1 ]] ||
  fail "Expected one Browser Curl for raw JSON"
if grep -q '^browsers playwright ' "$TEST_DIR/kernel.log"; then
  fail "Raw JSON unexpectedly used Playwright"
fi
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session"

reset_fake
challenge_output="$TEST_DIR/challenge.json"
run_updater_function block-then-raw "$challenge_output"
jq -e '.availability == {}' "$challenge_output" >/dev/null
[[ "$(grep -c '^browsers curl ' "$TEST_DIR/kernel.log")" -eq 2 ]] ||
  fail "Expected Browser Curl to retry a block page"
[[ "$(grep -c '^browsers playwright ' "$TEST_DIR/kernel.log")" -eq 1 ]] ||
  fail "Expected one Playwright challenge navigation"
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session"

reset_fake
failed_output="$TEST_DIR/failed.txt"
set +e
FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_MODE=fail \
  FAKE_KERNEL_DELETE_FAIL=true \
  FETCH_BACKEND=kernel \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  bash -c 'source "$1"; initialize_fetch_backend; kernel_browser_curl "https://example.com/data" "$2"' \
  _ \
  "$UPDATER" \
  "$TEST_DIR/failed.json" >"$failed_output" 2>&1
failed_status=$?
set -e
[[ "$failed_status" -eq 1 ]] ||
  fail "Kernel failure exit status changed during cleanup: $failed_status"
[[ "$(grep -c '^browsers curl ' "$TEST_DIR/kernel.log")" -eq 2 ]] ||
  fail "Expected a failed Browser Curl to retry once"
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session"
assert_contains "$failed_output" "Warning: Kernel session cleanup failed"

normal_page="$TEST_DIR/normal.html"
block_page="$TEST_DIR/block.html"
printf '%s\n' '<html><script src="/cdn-cgi/scripts/cloudflare-static/email-decode.min.js"></script><script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script></html>' >"$normal_page"
printf '%s\n' '<html><title>Attention Required! | Cloudflare</title><p>Cloudflare Ray ID: abc</p></html>' >"$block_page"
bash -c \
  'source "$1"; ! response_is_cloudflare_challenge "$2"; response_is_cloudflare_challenge "$3"' \
  _ \
  "$UPDATER" \
  "$normal_page" \
  "$block_page"

echo "update-availability tests passed"
