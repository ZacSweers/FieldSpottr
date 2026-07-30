#!/usr/bin/env bash

set -euo pipefail

TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KERNEL_LIBRARY="$TEST_ROOT/scripts/lib/kernel-browser.sh"
UPDATER="$TEST_ROOT/scripts/update-bbp-schedule.sh"
FAKE_KERNEL="$TEST_ROOT/scripts/testdata/fake-kernel.sh"
FAKE_GENERATOR="$TEST_ROOT/scripts/testdata/fake-bbp-generator.sh"
SOURCE_FILE="$TEST_ROOT/data/bbp/pier5-current.json"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fieldspottr-bbp-test.XXXXXX")"

cleanup() {
  rm -rf "$TEST_DIR" "$TEST_ROOT/build/bbp-refresh"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local pattern="$2"
  grep -Fq -- "$pattern" "$file" || fail "Expected $file to contain: $pattern"
}

assert_not_contains() {
  local file="$1"
  local pattern="$2"
  if grep -Fq -- "$pattern" "$file"; then
    fail "Expected $file not to contain: $pattern"
  fi
}

reset_fake() {
  : >"$TEST_DIR/kernel.log"
  : >"$TEST_DIR/generator.log"
  rm -f "$TEST_DIR/kernel.state"
}

prepare_into_capture() {
  local capture_root="$1"
  rm -rf "$capture_root"
  mkdir -p "$capture_root/data/bbp"
  cp -R "$TEST_ROOT/data/bbp/." "$capture_root/data/bbp/"

  INSTALL_CAPTURE_ROOT="$capture_root" \
    FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
    GRADLEW="$FAKE_GENERATOR" \
    bash -c '
      set -euo pipefail
      source "$1"
      target_files_are_clean() {
        return 0
      }
      install_staged_data() {
        local staged_source="$1"
        local staged_image="$2"
        local source_path="$3"
        local image_path="$4"
        mkdir -p \
          "$INSTALL_CAPTURE_ROOT/$(dirname "$source_path")" \
          "$INSTALL_CAPTURE_ROOT/$(dirname "$image_path")"
        cp "$staged_source" "$INSTALL_CAPTURE_ROOT/$source_path"
        cp "$staged_image" "$INSTALL_CAPTURE_ROOT/$image_path"
      }
      prepare_phase
    ' _ "$UPDATER"
}

[[ -s "$SOURCE_FILE" ]] || fail "Missing migrated BBP source: $SOURCE_FILE"
CURRENT_IMAGE_RELATIVE="$(jq -er '.imagePath' "$SOURCE_FILE")"
CURRENT_IMAGE="$TEST_ROOT/$CURRENT_IMAGE_RELATIVE"
CURRENT_IMAGE_URL="$(jq -er '.imageUrl' "$SOURCE_FILE")"
[[ -s "$CURRENT_IMAGE" ]] || fail "Missing migrated BBP image: $CURRENT_IMAGE"

webp_file="$TEST_DIR/schedule.webp"
printf '\x52\x49\x46\x46\x04\x00\x00\x00\x57\x45\x42\x50' >"$webp_file"
bash -c \
  'set -euo pipefail; source "$1"; validate_raster_image "$2" "https://brooklynbridgepark.org/wp-content/uploads/schedule.webp"; [[ "$CANDIDATE_EXTENSION" == "webp" ]]' \
  _ \
  "$UPDATER" \
  "$webp_file"

pdf_file="$TEST_DIR/schedule.pdf"
printf '%%PDF-1.7\n' >"$pdf_file"
if bash -c \
  'set -euo pipefail; source "$1"; validate_raster_image "$2" "https://brooklynbridgepark.org/wp-content/uploads/schedule.pdf"' \
  _ \
  "$UPDATER" \
  "$pdf_file" >"$TEST_DIR/pdf.txt" 2>&1; then
  fail "BBP raster validation accepted a PDF"
fi
assert_contains "$TEST_DIR/pdf.txt" "PDF transcription is not enabled"

unsupported_file="$TEST_DIR/schedule.bin"
printf '%s\n' "not an image" >"$unsupported_file"
if bash -c \
  'set -euo pipefail; source "$1"; validate_raster_image "$2" "https://brooklynbridgepark.org/wp-content/uploads/schedule.png"' \
  _ \
  "$UPDATER" \
  "$unsupported_file" >"$TEST_DIR/unsupported.txt" 2>&1; then
  fail "BBP raster validation accepted unsupported bytes"
fi
assert_contains "$TEST_DIR/unsupported.txt" "not a supported PNG, JPEG, or WebP image"

if bash -c \
  'set -euo pipefail; source "$1"; require_canonical_bbp_image_url "https://www.brooklynbridgepark.org/wp-content/uploads/schedule.png"' \
  _ \
  "$UPDATER" >"$TEST_DIR/noncanonical-url.txt" 2>&1; then
  fail "BBP URL validation accepted a noncanonical host"
fi
assert_contains "$TEST_DIR/noncanonical-url.txt" "canonical HTTPS uploads host"

reset_fake
binary_output="$TEST_DIR/binary-output.png"
FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_MODE=binary \
  FAKE_KERNEL_BINARY_FILE="$CURRENT_IMAGE" \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  bash -c \
  'set -euo pipefail; source "$1"; kernel_start_session; kernel_browser_fetch "https://example.com/image.png" "$2" binary; kernel_stop_session' \
  _ \
  "$KERNEL_LIBRARY" \
  "$binary_output"
cmp -s "$CURRENT_IMAGE" "$binary_output" || fail "Binary Kernel fetch changed image bytes"
[[ "$(grep -c '^browsers curl ' "$TEST_DIR/kernel.log")" -eq 1 ]] ||
  fail "Expected one Browser Curl for binary content"
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session"

reset_fake
binary_retry_output="$TEST_DIR/binary-retry-output.png"
FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_MODE=block-then-binary \
  FAKE_KERNEL_BINARY_FILE="$CURRENT_IMAGE" \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  bash -c \
  'set -euo pipefail; source "$1"; kernel_start_session; kernel_browser_fetch "https://example.com/image.png" "$2" binary; kernel_stop_session' \
  _ \
  "$KERNEL_LIBRARY" \
  "$binary_retry_output"
cmp -s "$CURRENT_IMAGE" "$binary_retry_output" ||
  fail "Binary Kernel challenge retry changed image bytes"
[[ "$(grep -c '^browsers curl ' "$TEST_DIR/kernel.log")" -eq 2 ]] ||
  fail "Expected Browser Curl to retry a binary challenge"
[[ "$(grep -c '^browsers playwright ' "$TEST_DIR/kernel.log")" -eq 1 ]] ||
  fail "Expected Playwright navigation for a binary challenge"

reset_fake
old_version_output="$TEST_DIR/old-version.txt"
if FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_VERSION=0.25.9 \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  bash -c 'set -euo pipefail; source "$1"; kernel_start_session' _ "$KERNEL_LIBRARY" \
  >"$old_version_output" 2>&1; then
  fail "Shared Kernel transport accepted an old CLI"
fi
assert_contains "$old_version_output" "Kernel CLI 0.26.0 or newer is required; found 0.25.9."

reset_fake
failed_fetch_output="$TEST_DIR/failed-fetch.txt"
if FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_MODE=fail \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  bash -c \
  'set -euo pipefail; source "$1"; kernel_start_session; kernel_browser_fetch "https://example.com/image.png" "$2" binary' \
  _ \
  "$KERNEL_LIBRARY" \
  "$TEST_DIR/failed-image.png" >"$failed_fetch_output" 2>&1; then
  fail "Shared Kernel transport accepted a failed Browser Curl"
fi
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session"

reset_fake
unchanged_output="$TEST_DIR/unchanged.txt"
source_before="$(shasum -a 256 "$SOURCE_FILE" | awk '{ print $1 }')"
image_before="$(shasum -a 256 "$CURRENT_IMAGE" | awk '{ print $1 }')"
env -u OPENAI_API_KEY \
  FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_MODE=bbp-image-block-then-image \
  FAKE_BBP_IMAGE_FILE="$CURRENT_IMAGE" \
  FAKE_BBP_IMAGE_URL="$CURRENT_IMAGE_URL" \
  FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
  GRADLEW="$FAKE_GENERATOR" \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  "$UPDATER" all >"$unchanged_output" 2>&1
assert_contains "$unchanged_output" "The checked-in BBP schedule image and URL are current."
assert_contains \
  "$unchanged_output" \
  "The checked-in Brooklyn Bridge Park source, image hash, expiry, and generated feed are valid."
assert_not_contains "$TEST_DIR/generator.log" "--transcribe-bbp-source="
[[ "$(jq -r '.change' "$TEST_ROOT/build/bbp-refresh/metadata.json")" == "none" ]] ||
  fail "Unchanged BBP fetch did not report change=none"
[[ "$source_before" == "$(shasum -a 256 "$SOURCE_FILE" | awk '{ print $1 }')" ]] ||
  fail "Unchanged BBP run modified the checked-in source"
[[ "$image_before" == "$(shasum -a 256 "$CURRENT_IMAGE" | awk '{ print $1 }')" ]] ||
  fail "Unchanged BBP run modified the checked-in image"
[[ "$(grep -c '^browsers curl ' "$TEST_DIR/kernel.log")" -eq 3 ]] ||
  fail "Expected page fetch plus challenged image retry"
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session"

invalid_output="$TEST_DIR/invalid-current.txt"
if FAKE_BBP_GENERATOR_MODE=validate-fail \
  FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
  GRADLEW="$FAKE_GENERATOR" \
  "$UPDATER" prepare >"$invalid_output" 2>&1; then
  fail "Prepare accepted an invalid unchanged BBP source"
fi
assert_contains "$invalid_output" "Brooklyn Bridge Park candidate source validation failed."

expired_output="$TEST_DIR/expired-current.txt"
if FAKE_BBP_GENERATOR_MODE=validate-expired \
  FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
  GRADLEW="$FAKE_GENERATOR" \
  "$UPDATER" prepare >"$expired_output" 2>&1; then
  fail "Prepare accepted an expired unchanged BBP source"
fi
assert_contains "$expired_output" "candidate expired on 2026-01-01"

reset_fake
url_only_image_url="${CURRENT_IMAGE_URL%.*}-canonical-copy.png"
env -u OPENAI_API_KEY \
  FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_MODE=bbp \
  FAKE_BBP_IMAGE_FILE="$CURRENT_IMAGE" \
  FAKE_BBP_IMAGE_URL="$url_only_image_url" \
  FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
  GRADLEW="$FAKE_GENERATOR" \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  "$UPDATER" fetch >"$TEST_DIR/url-only-fetch.txt" 2>&1
[[ "$(jq -r '.change' "$TEST_ROOT/build/bbp-refresh/metadata.json")" == "url" ]] ||
  fail "Same image bytes at a new URL did not report change=url"
url_capture="$TEST_DIR/url-capture"
unset OPENAI_API_KEY || true
prepare_into_capture "$url_capture" >"$TEST_DIR/url-only-prepare.txt" 2>&1
[[ "$(jq -r '.imageUrl' "$url_capture/data/bbp/pier5-current.json")" == "$url_only_image_url" ]] ||
  fail "URL-only preparation did not update imageUrl"
cmp -s "$CURRENT_IMAGE" "$url_capture/$CURRENT_IMAGE_RELATIVE" ||
  fail "URL-only preparation changed the immutable image"
assert_not_contains "$TEST_DIR/generator.log" "--transcribe-bbp-source="
url_diff="$TEST_DIR/url-only.diff"
diff -qr "$TEST_ROOT/data/bbp" "$url_capture/data/bbp" >"$url_diff" || true
[[ "$(wc -l <"$url_diff" | tr -d '[:space:]')" -eq 1 ]] ||
  fail "URL-only preparation changed files other than the current JSON"
assert_contains "$url_diff" "pier5-current.json"

changed_image="$TEST_DIR/changed.png"
cp "$CURRENT_IMAGE" "$changed_image"
printf 'changed' >>"$changed_image"

reset_fake
changed_no_key_output="$TEST_DIR/changed-no-key.txt"
if env -u OPENAI_API_KEY \
  FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_MODE=bbp \
  FAKE_BBP_IMAGE_FILE="$changed_image" \
  FAKE_BBP_IMAGE_URL="$CURRENT_IMAGE_URL" \
  FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
  GRADLEW="$FAKE_GENERATOR" \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  "$UPDATER" all >"$changed_no_key_output" 2>&1; then
  fail "Changed BBP content did not require OPENAI_API_KEY"
fi
assert_contains \
  "$changed_no_key_output" \
  "OPENAI_API_KEY is required because the BBP schedule image content changed."
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session"
assert_not_contains "$TEST_DIR/generator.log" "--transcribe-bbp-source="

changed_sha256="$(shasum -a 256 "$changed_image" | awk '{ print $1 }')"
changed_image_path="data/bbp/pier5-$changed_sha256.png"
changed_source="$TEST_DIR/changed-source.json"
jq \
  --arg imagePath "$changed_image_path" \
  --arg imageSha256 "$changed_sha256" \
  '.imagePath = $imagePath |
   .imageSha256 = $imageSha256 |
   .provenance.method = "openai" |
   .provenance.model = "fake-model" |
   .provenance.promptVersion = "bbp-pier5-v1" |
   .provenance.responseIds = ["resp_1", "resp_2"] |
   .provenance.extractedAt = "2026-07-30T00:00:00Z"' \
  "$SOURCE_FILE" >"$changed_source"
FAKE_BBP_TRANSCRIBED_SOURCE="$changed_source" \
  FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
  GRADLEW="$FAKE_GENERATOR" \
  OPENAI_API_KEY=test-openai-key \
  "$UPDATER" transcribe >"$TEST_DIR/transcribe.txt" 2>&1
assert_contains "$TEST_DIR/generator.log" "--transcribe-bbp-source="
[[ -s "$TEST_ROOT/build/bbp-refresh/diagnostics/extraction-1.json" ]] ||
  fail "Missing first sanitized extraction diagnostic"
[[ -s "$TEST_ROOT/build/bbp-refresh/diagnostics/extraction-2.json" ]] ||
  fail "Missing second sanitized extraction diagnostic"
changed_capture="$TEST_DIR/changed-capture"
prepare_into_capture "$changed_capture" >"$TEST_DIR/changed-prepare.txt" 2>&1
[[ -s "$changed_capture/$changed_image_path" ]] ||
  fail "Changed-content preparation did not install the new hash-named image"
cmp -s "$changed_image" "$changed_capture/$changed_image_path" ||
  fail "Changed-content preparation altered the new image bytes"
[[ -s "$changed_capture/$CURRENT_IMAGE_RELATIVE" ]] ||
  fail "Changed-content preparation removed the previous immutable image"
[[ "$(jq -r '.imagePath' "$changed_capture/data/bbp/pier5-current.json")" == "$changed_image_path" ]] ||
  fail "Changed-content preparation did not point current JSON at the new image"

reset_fake
weekly_failure_output="$TEST_DIR/weekly-failure.txt"
if FAKE_KERNEL_LOG="$TEST_DIR/kernel.log" \
  FAKE_KERNEL_STATE="$TEST_DIR/kernel.state" \
  FAKE_KERNEL_MODE=bbp-image-fail \
  FAKE_BBP_IMAGE_URL="$CURRENT_IMAGE_URL" \
  FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
  GRADLEW="$FAKE_GENERATOR" \
  KERNEL_API_KEY=test-key \
  KERNEL_CLI="$FAKE_KERNEL" \
  "$UPDATER" fetch >"$weekly_failure_output" 2>&1; then
  fail "Weekly BBP fetch accepted an image failure"
fi
assert_contains "$TEST_DIR/kernel.log" "browsers delete test-session"

same_semantics="$TEST_DIR/same-semantics.json"
jq \
  '.id = "different-provenance-id" |
   .provenance.method = "openai" |
   .provenance.model = "fake-model" |
   .provenance.promptVersion = "bbp-pier5-v1" |
   .provenance.responseIds = ["resp_1", "resp_2"] |
   .provenance.extractedAt = "2026-07-30T00:00:00Z"' \
  "$SOURCE_FILE" >"$same_semantics"
FAKE_BBP_TRANSCRIBED_SOURCE="$same_semantics" \
  FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
  GRADLEW="$FAKE_GENERATOR" \
  OPENAI_API_KEY=test-openai-key \
  "$UPDATER" verify-current >"$TEST_DIR/verify-current.txt" 2>&1
assert_contains \
  "$TEST_DIR/verify-current.txt" \
  "The checked-in Brooklyn Bridge Park transcription matches two fresh reads."

different_semantics="$TEST_DIR/different-semantics.json"
jq '.blocks[0].end = "22:30"' "$same_semantics" >"$different_semantics"
if FAKE_BBP_TRANSCRIBED_SOURCE="$different_semantics" \
  FAKE_BBP_GENERATOR_LOG="$TEST_DIR/generator.log" \
  GRADLEW="$FAKE_GENERATOR" \
  OPENAI_API_KEY=test-openai-key \
  "$UPDATER" verify-current >"$TEST_DIR/verify-mismatch.txt" 2>&1; then
  fail "verify-current accepted different BBP schedule semantics"
fi
assert_contains \
  "$TEST_DIR/verify-mismatch.txt" \
  "The checked-in Brooklyn Bridge Park transcription differs from two fresh reads."

echo "update-bbp-schedule tests passed"
