#!/usr/bin/env bash

set -euo pipefail

TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPDATER="$TEST_ROOT/scripts/update-availability.sh"
FAKE_KERNEL="$TEST_ROOT/scripts/testdata/fake-kernel.sh"
RETRY_UPDATER="$TEST_ROOT/scripts/update-availability-retry.sh"
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
  rm -f "$TEST_DIR/sessions" "$TEST_DIR/backoff.log"
  rm -rf "$TEST_DIR/live" "$TEST_DIR/attempt-1" "$TEST_DIR/attempt-2"
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
FAKE_KERNEL_WARNING=true run_updater_function block-then-raw "$challenge_output"
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

reset_fake
navigation_output="$TEST_DIR/navigation-failed.txt"
if FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_MODE=block-then-raw \
  FAKE_KERNEL_PLAYWRIGHT_SUCCESS=false \
  KERNEL_DIAGNOSTICS_DIR="$TEST_DIR/navigation-diagnostics" \
  FETCH_BACKEND=kernel \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  bash -c 'set -euo pipefail; source "$1"; initialize_fetch_backend; kernel_browser_curl "https://example.com/data" "$2"' \
  _ "$UPDATER" "$TEST_DIR/navigation.json" >"$navigation_output" 2>&1; then
  fail "Kernel accepted Playwright success=false with CLI exit zero"
fi
[[ ! -e "$TEST_DIR/navigation.json" ]] || fail "Failed navigation left a source file"
[[ "$(grep -c '^browsers curl ' "$TEST_DIR/kernel.log")" -eq 1 ]] ||
  fail "Kernel retried Browser Curl after failed navigation"
assert_contains "$navigation_output" "network_error"
jq -es 'any(.[]; .operation == "playwright" and .cliExitCode == 0 and .success == false)' \
  "$TEST_DIR/navigation-diagnostics"/*.json >/dev/null ||
  fail "Missing semantic Playwright failure diagnostic"
if grep -Eq 'secret-token|secret-cookie|signed-test-url' \
  "$navigation_output" "$TEST_DIR/navigation-diagnostics"/*.json; then
  fail "Navigation diagnostics leaked response secrets"
fi

run_retry_case() {
  local mode="$1"
  local output="$2"
  FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
    FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
    FAKE_KERNEL_SESSION_COUNTER="$TEST_DIR/sessions" \
    FAKE_KERNEL_MODE="$mode" \
    FETCH_BACKEND=kernel \
    REQUIRE_FRESH_LIVE_SOURCES=true \
    KERNEL_API_KEY=test-key \
    KERNEL_CLI="$FAKE_KERNEL" \
    bash -c '
      set -euo pipefail
      source "$1"
      TEST_UPDATER="$2"
      export TEST_DIR="$3"
      run_availability_attempt() {
        KERNEL_DIAGNOSTICS_DIR="$TEST_DIR/attempt-$1" \
          bash -c '\''set -euo pipefail; source "$1"; NYC_LIVE_DIR="$2/live"; initialize_fetch_backend; fetch_nyc_live_sources 2026-10-06'\'' \
          _ "$TEST_UPDATER" "$TEST_DIR"
      }
      sleep() { printf "%s\n" "$1" >>"$TEST_DIR/backoff.log"; }
      refresh_availability_with_retry
    ' _ "$RETRY_UPDATER" "$UPDATER" "$TEST_DIR" >"$output" 2>&1
}

reset_fake
first_attempt_output="$TEST_DIR/first-attempt-success.txt"
FAKE_KERNEL_WARNING=true run_retry_case raw "$first_attempt_output"
[[ "$(grep -c '^browsers create ' "$TEST_DIR/kernel.log")" -eq 1 ]] ||
  fail "Successful refresh unnecessarily created a second session"
[[ ! -e "$TEST_DIR/backoff.log" ]] || fail "Successful refresh waited for a retry"
if grep -Eq 'secret-token|signed-test-url' "$first_attempt_output" "$TEST_DIR/attempt-1"/*.json; then
  fail "CLI warnings leaked into diagnostics"
fi

reset_fake
retry_output="$TEST_DIR/retry-success.txt"
run_retry_case fail-first-session "$retry_output"
[[ "$(grep -c '^browsers create ' "$TEST_DIR/kernel.log")" -eq 2 ]] ||
  fail "Retry did not create two fresh sessions"
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session-1"
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session-2"
[[ "$(<"$TEST_DIR/backoff.log")" == 15 ]] || fail "Retry backoff changed"
assert_contains "$retry_output" "attempt 2 of 2"
jq -e '.availability == {}' "$TEST_DIR/live/M165-BASEBALL-1/2026-10-06.json" >/dev/null
jq -es 'any(.[]; .httpStatus == 503 and .success == false)' \
  "$TEST_DIR/attempt-1"/*.json >/dev/null || fail "Missing failed HTTP status"
if grep -Eq 'secret-token|secret-cookie|signed-test-url' \
  "$retry_output" "$TEST_DIR/attempt-1"/*.json "$TEST_DIR/attempt-2"/*.json; then
  fail "Retry diagnostics leaked response secrets"
fi

reset_fake
semantic_retry_output="$TEST_DIR/semantic-retry-success.txt"
FAKE_KERNEL_PLAYWRIGHT_SUCCESS=false run_retry_case fail-first-session "$semantic_retry_output"
[[ "$(grep -c '^browsers create ' "$TEST_DIR/kernel.log")" -eq 2 ]] ||
  fail "Failed navigation did not renew the browser session"
jq -es 'any(.[]; .operation == "playwright" and .cliExitCode == 0 and .success == false)' \
  "$TEST_DIR/attempt-1"/*.json >/dev/null || fail "Missing failed navigation before retry"
jq -e '.availability == {}' "$TEST_DIR/live/M165-BASEBALL-1/2026-10-06.json" >/dev/null

reset_fake
exhausted_output="$TEST_DIR/retry-exhausted.txt"
if run_retry_case fail "$exhausted_output"; then
  fail "Availability refresh accepted exhausted retries"
fi
[[ "$(grep -c '^browsers create ' "$TEST_DIR/kernel.log")" -eq 2 ]] ||
  fail "Exhaustion did not stop after two sessions"
[[ "$(grep -c '^browsers curl ' "$TEST_DIR/kernel.log")" -eq 4 ]] ||
  fail "Strict refresh did not stop at the first failed NYC source"
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session-1"
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session-2"
assert_contains "$exhausted_output" "failed after two attempts"
[[ ! -e "$TEST_DIR/live/M165-BASEBALL-1/2026-10-06.json" ]] ||
  fail "Exhausted retry left an accepted source"
if compgen -G "$TEST_DIR/live/*/*" >/dev/null; then
  fail "Failed fetch retained a raw response"
fi

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
