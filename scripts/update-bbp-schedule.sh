#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/lib/kernel-browser.sh"

REFRESH_DIR="$ROOT/build/bbp-refresh"
STAGED_DIR="$REFRESH_DIR/staged"
GENERATED_DIR="$REFRESH_DIR/generated"
METADATA_FILE="$REFRESH_DIR/metadata.json"
SUMMARY_FILE="$REFRESH_DIR/summary.json"
CANDIDATE_SOURCE_FILE="$REFRESH_DIR/candidate-source.json"
VALIDATED_SOURCE_FILE="$REFRESH_DIR/validated-source.json"
TRANSCRIBE_LOG="$REFRESH_DIR/transcribe.log"
VALIDATION_LOG="$REFRESH_DIR/validation.log"
PR_BODY_FILE="$REFRESH_DIR/pr-body.md"
BBP_SOURCE_PAGE_URL="https://brooklynbridgepark.org/places-to-see/pier-5/"
BBP_UPLOADS_PREFIX="https://brooklynbridgepark.org/wp-content/uploads/"
MAX_IMAGE_BYTES=$((20 * 1024 * 1024))
GRADLEW="${GRADLEW:-$ROOT/gradlew}"

SOURCE_FILE="${BBP_SOURCE_FILE:-data/bbp/pier5-current.json}"

fail() {
  echo "$*" >&2
  return 1
}

require_command() {
  local command_name="$1"
  local installation_hint="$2"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    fail "$command_name is required. $installation_hint"
  fi
}

repo_path() {
  local path="$1"
  if [[ "$path" == /* ]]; then
    printf '%s\n' "$path"
  else
    printf '%s\n' "$ROOT/$path"
  fi
}

require_repo_data_path() {
  local path="$1"
  local label="$2"

  if [[ "$path" == /* || "$path" != data/bbp/* || "$path" == *".."* || "$path" == *"\\"* ]]; then
    fail "$label must be a repository-relative path under data/bbp: $path"
  fi
}

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{ print $1 }'
  else
    shasum -a 256 "$file" | awk '{ print $1 }'
  fi
}

file_size() {
  local file="$1"
  wc -c <"$file" | tr -d '[:space:]'
}

run_generator() {
  local args="$1"
  "$GRADLEW" --quiet :generator:run --args="$args"
}

write_github_output() {
  local name="$1"
  local value="$2"

  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$name" "$value" >>"$GITHUB_OUTPUT"
  fi
}

reset_refresh_dir() {
  rm -rf "$REFRESH_DIR"
  mkdir -p "$REFRESH_DIR"
}

require_refresh_metadata() {
  if [[ ! -s "$METADATA_FILE" ]] || ! jq empty "$METADATA_FILE" >/dev/null 2>&1; then
    fail "Missing BBP fetch metadata. Run scripts/update-bbp-schedule.sh fetch first."
  fi
}

require_current_source() {
  CURRENT_SOURCE_ABS="$(repo_path "$SOURCE_FILE")"
  if [[ ! -s "$CURRENT_SOURCE_ABS" ]] || ! jq empty "$CURRENT_SOURCE_ABS" >/dev/null 2>&1; then
    fail "Brooklyn Bridge Park source is missing or invalid: $CURRENT_SOURCE_ABS"
  fi

  CURRENT_IMAGE_PATH="$(jq -er '.imagePath | strings | select(length > 0)' "$CURRENT_SOURCE_ABS")"
  require_repo_data_path "$CURRENT_IMAGE_PATH" "Brooklyn Bridge Park imagePath"
  CURRENT_IMAGE_ABS="$(repo_path "$CURRENT_IMAGE_PATH")"
  if [[ ! -s "$CURRENT_IMAGE_ABS" ]]; then
    fail "Brooklyn Bridge Park image is missing or empty: $CURRENT_IMAGE_ABS"
  fi

  CURRENT_IMAGE_URL="$(jq -er '.imageUrl | strings | select(length > 0)' "$CURRENT_SOURCE_ABS")"
  CURRENT_SOURCE_PAGE_URL="$(
    jq -er '.sourcePageUrl | strings | select(length > 0)' "$CURRENT_SOURCE_ABS"
  )"
  CURRENT_SHA256="$(sha256_file "$CURRENT_IMAGE_ABS")"
}

require_canonical_bbp_image_url() {
  local url="$1"
  local suffix

  if [[ "$url" != "$BBP_UPLOADS_PREFIX"* ]]; then
    fail "Discovered BBP image URL must use the canonical HTTPS uploads host: $url"
  fi
  suffix="${url#"$BBP_UPLOADS_PREFIX"}"
  if [[ -z "$suffix" || "$suffix" == *"?"* || "$suffix" == *"#"* || "$suffix" == *".."* ||
    "$suffix" == *"\\"* || "$suffix" == *[$' \t\r\n']* ]]; then
    fail "Discovered BBP image URL is not a canonical uploads path: $url"
  fi
}

schedule_year_from_url() {
  local url="$1"
  local year

  year="$(
    printf '%s\n' "$url" |
      grep -Eo '20[0-9]{2}' |
      tail -n 1 || true
  )"
  if [[ ! "$year" =~ ^20[0-9]{2}$ ]]; then
    fail "Could not determine the BBP schedule year from: $url"
  fi
  printf '%s\n' "$year"
}

validate_raster_image() {
  local file="$1"
  local url="$2"
  local bytes
  local magic
  local url_path

  bytes="$(file_size "$file")"
  if ((bytes <= 0)); then
    fail "Downloaded BBP schedule image is empty."
  fi
  if ((bytes > MAX_IMAGE_BYTES)); then
    fail "Downloaded BBP schedule image exceeds the 20 MB limit: $bytes bytes"
  fi

  magic="$(od -An -tx1 -N12 "$file" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
  url_path="$(printf '%s' "$url" | tr '[:upper:]' '[:lower:]')"
  case "$magic" in
    89504e470d0a1a0a*)
      if [[ "$url_path" != *.png ]]; then
        fail "BBP image bytes are PNG but the canonical URL is not a .png asset: $url"
      fi
      CANDIDATE_EXTENSION="png"
      ;;
    ffd8ff*)
      if [[ "$url_path" != *.jpg && "$url_path" != *.jpeg ]]; then
        fail "BBP image bytes are JPEG but the canonical URL is not a .jpg or .jpeg asset: $url"
      fi
      CANDIDATE_EXTENSION="jpg"
      ;;
    52494646????????57454250*)
      if [[ "$url_path" != *.webp ]]; then
        fail "BBP image bytes are WebP but the canonical URL is not a .webp asset: $url"
      fi
      CANDIDATE_EXTENSION="webp"
      ;;
    255044462d*)
      fail "BBP published a PDF schedule; PDF transcription is not enabled for this workflow."
      ;;
    *)
      fail "Downloaded BBP schedule asset is not a supported PNG, JPEG, or WebP image."
      ;;
  esac
}

fetch_phase() {
  cd "$ROOT"
  require_command jq "Install it with: brew install jq"
  require_command od ""
  require_current_source
  reset_refresh_dir

  local page_file="$REFRESH_DIR/page.html"
  local downloaded_file="$REFRESH_DIR/candidate-image.download"
  local candidate_file
  local image_url
  local schedule_year
  local candidate_sha256
  local current_bytes
  local candidate_bytes
  local change

  kernel_start_session
  echo "Fetching the Brooklyn Bridge Park Pier 5 page"
  kernel_browser_fetch "$BBP_SOURCE_PAGE_URL" "$page_file" text

  image_url="$(run_generator "--discover-bbp-image=$page_file")"
  require_canonical_bbp_image_url "$image_url"
  schedule_year="$(schedule_year_from_url "$image_url")"

  echo "Fetching the Brooklyn Bridge Park Pier 5 schedule image"
  kernel_browser_fetch "$image_url" "$downloaded_file" binary
  validate_raster_image "$downloaded_file" "$image_url"
  candidate_file="$REFRESH_DIR/candidate-image.$CANDIDATE_EXTENSION"
  mv "$downloaded_file" "$candidate_file"

  if ! kernel_stop_session; then
    fail "Could not delete the Kernel browser session before leaving the fetch phase."
  fi

  candidate_sha256="$(sha256_file "$candidate_file")"
  current_bytes="$(file_size "$CURRENT_IMAGE_ABS")"
  candidate_bytes="$(file_size "$candidate_file")"
  if cmp -s "$CURRENT_IMAGE_ABS" "$candidate_file"; then
    if [[ "$CURRENT_IMAGE_URL" == "$image_url" ]]; then
      change="none"
    else
      change="url"
    fi
  else
    change="content"
  fi

  jq -n \
    --arg sourcePageUrl "$BBP_SOURCE_PAGE_URL" \
    --arg currentSourceFile "$SOURCE_FILE" \
    --arg currentImagePath "$CURRENT_IMAGE_PATH" \
    --arg currentImageUrl "$CURRENT_IMAGE_URL" \
    --arg imageUrl "$image_url" \
    --arg candidateImage "$candidate_file" \
    --arg scheduleYear "$schedule_year" \
    --arg currentSha256 "$CURRENT_SHA256" \
    --arg candidateSha256 "$candidate_sha256" \
    --arg change "$change" \
    --argjson currentBytes "$current_bytes" \
    --argjson candidateBytes "$candidate_bytes" \
    '{
      sourcePageUrl: $sourcePageUrl,
      currentSourceFile: $currentSourceFile,
      currentImagePath: $currentImagePath,
      currentImageUrl: $currentImageUrl,
      imageUrl: $imageUrl,
      candidateImage: $candidateImage,
      scheduleYear: $scheduleYear,
      currentSha256: $currentSha256,
      candidateSha256: $candidateSha256,
      currentBytes: $currentBytes,
      candidateBytes: $candidateBytes,
      change: $change
    }' >"$METADATA_FILE"
  cp "$METADATA_FILE" "$SUMMARY_FILE"

  local changed=false
  local content_changed=false
  if [[ "$change" != "none" ]]; then
    changed=true
  fi
  if [[ "$change" == "content" ]]; then
    content_changed=true
  fi

  write_github_output change "$change"
  write_github_output changed "$changed"
  write_github_output content_changed "$content_changed"
  write_github_output image_url "$image_url"
  write_github_output schedule_year "$schedule_year"
  write_github_output current_sha256 "$CURRENT_SHA256"
  write_github_output candidate_sha256 "$candidate_sha256"

  case "$change" in
    none) echo "The checked-in BBP schedule image and URL are current." ;;
    url) echo "The BBP image URL changed, but the downloaded bytes are unchanged." ;;
    content) echo "The BBP schedule image content changed and requires transcription." ;;
  esac
}

transcribe_phase() {
  cd "$ROOT"
  require_command jq "Install it with: brew install jq"
  require_refresh_metadata

  local change
  change="$(jq -er '.change' "$METADATA_FILE")"
  if [[ "$change" != "content" ]]; then
    echo "BBP image content did not change; transcription is not required."
    return 0
  fi
  if [[ -z "${OPENAI_API_KEY:-}" ]]; then
    fail "OPENAI_API_KEY is required because the BBP schedule image content changed."
  fi

  local image_file
  local image_url
  local source_page_url
  local schedule_year
  local args
  image_file="$(jq -er '.candidateImage' "$METADATA_FILE")"
  image_url="$(jq -er '.imageUrl' "$METADATA_FILE")"
  source_page_url="$(jq -er '.sourcePageUrl' "$METADATA_FILE")"
  schedule_year="$(jq -er '.scheduleYear' "$METADATA_FILE")"
  if [[ ! -s "$image_file" ]]; then
    fail "Fetched BBP schedule image is missing: $image_file"
  fi

  rm -rf "$REFRESH_DIR/diagnostics"
  rm -f "$CANDIDATE_SOURCE_FILE" "$TRANSCRIBE_LOG"
  args="--transcribe-bbp-source=$image_file --bbp-image-url=$image_url --bbp-source-page-url=$source_page_url --bbp-schedule-year=$schedule_year --bbp-source-output=$CANDIDATE_SOURCE_FILE --bbp-diagnostics-dir=$REFRESH_DIR/diagnostics"
  if ! run_generator "$args" >"$TRANSCRIBE_LOG" 2>&1; then
    sed -n '1,200p' "$TRANSCRIBE_LOG" >&2
    rm -f "$CANDIDATE_SOURCE_FILE"
    fail "Brooklyn Bridge Park schedule transcription failed."
  fi
  sed -n '1,200p' "$TRANSCRIBE_LOG" >&2
  if [[ ! -s "$CANDIDATE_SOURCE_FILE" ]] || ! jq empty "$CANDIDATE_SOURCE_FILE" >/dev/null 2>&1; then
    rm -f "$CANDIDATE_SOURCE_FILE"
    fail "Brooklyn Bridge Park transcription did not produce a valid candidate source."
  fi

  echo "The two-pass BBP schedule transcription produced a candidate source."
}

write_pr_body() {
  local source_file="$1"
  local change="$2"
  local image_url="$3"
  local current_sha256="$4"
  local candidate_sha256="$5"
  local valid_from
  local valid_to
  local block_count

  valid_from="$(jq -er '.validFrom' "$source_file")"
  valid_to="$(jq -er '.validTo' "$source_file")"
  block_count="$(jq -er '.blocks | length' "$source_file")"

  {
    printf '%s\n\n' "Automated Brooklyn Bridge Park Pier 5 schedule source refresh."
    printf '%s\n' "| Item | Value |"
    printf '%s\n' "| --- | --- |"
    printf '| Source page | %s |\n' "$BBP_SOURCE_PAGE_URL"
    printf '| Schedule image | %s |\n' "$image_url"
    printf '| Change | %s |\n' "$change"
    printf '| Previous SHA-256 | `%s` |\n' "$current_sha256"
    printf '| New SHA-256 | `%s` |\n' "$candidate_sha256"
    printf '| Valid dates | %s through %s |\n' "$valid_from" "$valid_to"
    printf '| Recurring blocks | %s |\n\n' "$block_count"
    printf '%s\n\n' "Schedule transcription:"
    printf '%s\n' "| Day | Fields | Start | End |"
    printf '%s\n' "| --- | --- | --- | --- |"
    jq -r \
      '.blocks[] | "| \(.day) | \(.fieldIds | join(", ")) | \(.start) | \(.end) |"' \
      "$source_file"
    printf '\n%s\n' "Review the schedule image and transcription before merging."
  } >"$PR_BODY_FILE"
}

target_files_are_clean() {
  local source_path="$1"
  local new_image_path="$2"
  local status

  status="$(git status --porcelain -- "$source_path" "$new_image_path")"
  if [[ -n "$status" ]]; then
    printf '%s\n' "$status" >&2
    fail "Refusing to overwrite dirty Brooklyn Bridge Park source files."
  fi
}

install_staged_data() {
  local staged_source="$1"
  local staged_image="$2"
  local source_path="$3"
  local image_path="$4"
  local source_abs
  local image_abs
  local source_temp
  local image_temp

  source_abs="$(repo_path "$source_path")"
  image_abs="$(repo_path "$image_path")"
  source_temp="${source_abs}.bbp-update.$$"
  image_temp="${image_abs}.bbp-update.$$"

  mkdir -p "$(dirname "$source_abs")" "$(dirname "$image_abs")"
  cp "$staged_source" "$source_temp"
  cp "$staged_image" "$image_temp"
  mv "$image_temp" "$image_abs"
  mv "$source_temp" "$source_abs"
}

prepare_phase() {
  cd "$ROOT"
  require_command jq "Install it with: brew install jq"
  require_refresh_metadata
  require_current_source

  local change
  local image_url
  local current_sha256
  local candidate_sha256
  local candidate_image
  local candidate_image_path
  local staged_source
  local staged_image
  local args
  change="$(jq -er '.change' "$METADATA_FILE")"
  image_url="$(jq -er '.imageUrl' "$METADATA_FILE")"
  current_sha256="$(jq -er '.currentSha256' "$METADATA_FILE")"
  candidate_sha256="$(jq -er '.candidateSha256' "$METADATA_FILE")"

  rm -rf "$STAGED_DIR" "$GENERATED_DIR"
  rm -f "$VALIDATED_SOURCE_FILE" "$VALIDATION_LOG" "$PR_BODY_FILE"
  mkdir -p "$STAGED_DIR/data/bbp" "$GENERATED_DIR"
  cp -R "$ROOT/data/bbp/." "$STAGED_DIR/data/bbp/"

  case "$change" in
    none)
      cp "$CURRENT_SOURCE_ABS" "$CANDIDATE_SOURCE_FILE"
      candidate_image="$CURRENT_IMAGE_ABS"
      ;;
    url)
      jq --arg imageUrl "$image_url" '.imageUrl = $imageUrl' \
        "$CURRENT_SOURCE_ABS" >"$CANDIDATE_SOURCE_FILE"
      candidate_image="$CURRENT_IMAGE_ABS"
      ;;
    content)
      if [[ ! -s "$CANDIDATE_SOURCE_FILE" ]]; then
        fail "Missing transcribed BBP candidate source. Run the transcribe phase first."
      fi
      candidate_image="$(jq -er '.candidateImage' "$METADATA_FILE")"
      ;;
    *)
      fail "Unknown BBP change type: $change"
      ;;
  esac

  require_repo_data_path "$SOURCE_FILE" "Brooklyn Bridge Park source file"
  candidate_image_path="$(
    jq -er '.imagePath | strings | select(length > 0)' "$CANDIDATE_SOURCE_FILE"
  )"
  require_repo_data_path "$candidate_image_path" "Candidate Brooklyn Bridge Park imagePath"
  if [[ ! -s "$candidate_image" ]]; then
    fail "Candidate Brooklyn Bridge Park image is missing: $candidate_image"
  fi
  if [[ "$(sha256_file "$candidate_image")" != "$candidate_sha256" ]]; then
    fail "Candidate Brooklyn Bridge Park image hash changed after the fetch phase."
  fi

  staged_source="$STAGED_DIR/$SOURCE_FILE"
  staged_image="$STAGED_DIR/$candidate_image_path"
  mkdir -p "$(dirname "$staged_source")" "$(dirname "$staged_image")"
  cp "$CANDIDATE_SOURCE_FILE" "$staged_source"
  cp "$candidate_image" "$staged_image"

  args="--validate-bbp-source=$staged_source --bbp-image-root=$STAGED_DIR"
  if ! run_generator "$args" >"$VALIDATED_SOURCE_FILE" 2>"$VALIDATION_LOG"; then
    sed -n '1,200p' "$VALIDATION_LOG" >&2
    fail "Brooklyn Bridge Park candidate source validation failed."
  fi
  if [[ ! -s "$VALIDATED_SOURCE_FILE" ]] ||
    ! jq empty "$VALIDATED_SOURCE_FILE" >/dev/null 2>&1; then
    fail "Brooklyn Bridge Park validation did not produce a canonical source preview."
  fi
  cp "$VALIDATED_SOURCE_FILE" "$staged_source"

  args="--generate-bbp-only --bbp-source-file=$staged_source --bbp-image-root=$STAGED_DIR --baseline-output=$ROOT --output=$GENERATED_DIR"
  if ! run_generator "$args" >>"$VALIDATION_LOG" 2>&1; then
    sed -n '1,240p' "$VALIDATION_LOG" >&2
    fail "Focused Brooklyn Bridge Park generation failed."
  fi
  if [[ ! -s "$GENERATED_DIR/availability/areas/brooklyn-bridge-park.json" ||
    ! -s "$GENERATED_DIR/availability/manifest.json" ]]; then
    fail "Focused Brooklyn Bridge Park generation did not produce its validation outputs."
  fi

  local valid_from
  local valid_to
  local block_count
  valid_from="$(jq -er '.validFrom' "$VALIDATED_SOURCE_FILE")"
  valid_to="$(jq -er '.validTo' "$VALIDATED_SOURCE_FILE")"
  block_count="$(jq -er '.blocks | length' "$VALIDATED_SOURCE_FILE")"
  jq \
    --arg validFrom "$valid_from" \
    --arg validTo "$valid_to" \
    --argjson blockCount "$block_count" \
    '. + {validFrom: $validFrom, validTo: $validTo, blockCount: $blockCount}' \
    "$METADATA_FILE" >"$SUMMARY_FILE"

  if [[ "$change" == "none" ]]; then
    write_github_output prepared false
    echo "The checked-in Brooklyn Bridge Park source, image hash, expiry, and generated feed are valid."
    return 0
  fi

  write_pr_body \
    "$VALIDATED_SOURCE_FILE" \
    "$change" \
    "$image_url" \
    "$current_sha256" \
    "$candidate_sha256"

  target_files_are_clean "$SOURCE_FILE" "$candidate_image_path"
  install_staged_data \
    "$staged_source" \
    "$staged_image" \
    "$SOURCE_FILE" \
    "$candidate_image_path"

  write_github_output prepared true
  echo "Installed the validated Brooklyn Bridge Park source under data/bbp."
}

verify_current_phase() {
  cd "$ROOT"
  require_command jq "Install it with: brew install jq"
  require_current_source
  if [[ -z "${OPENAI_API_KEY:-}" ]]; then
    fail "OPENAI_API_KEY is required to verify the checked-in BBP transcription."
  fi

  reset_refresh_dir
  mkdir -p "$STAGED_DIR"

  local schedule_year
  local candidate_source="$REFRESH_DIR/verify-current-candidate.json"
  local current_canonical="$REFRESH_DIR/verify-current-checked-in.json"
  local candidate_canonical="$REFRESH_DIR/verify-current-transcribed.json"
  local current_semantics="$REFRESH_DIR/verify-current-checked-in-semantics.json"
  local candidate_semantics="$REFRESH_DIR/verify-current-transcribed-semantics.json"
  local verify_diff="$REFRESH_DIR/verify-current.diff"
  local staged_image
  local candidate_image_path
  local args
  schedule_year="$(jq -er '.validFrom | split("-")[0]' "$CURRENT_SOURCE_ABS")"

  if ! run_generator \
    "--validate-bbp-source=$CURRENT_SOURCE_ABS --bbp-image-root=$ROOT" \
    >"$current_canonical" 2>"$VALIDATION_LOG"; then
    fail "The checked-in Brooklyn Bridge Park source did not validate."
  fi

  args="--transcribe-bbp-source=$CURRENT_IMAGE_ABS --bbp-image-url=$CURRENT_IMAGE_URL --bbp-source-page-url=$CURRENT_SOURCE_PAGE_URL --bbp-schedule-year=$schedule_year --bbp-source-output=$candidate_source --bbp-diagnostics-dir=$REFRESH_DIR/diagnostics"
  if ! run_generator "$args" >"$TRANSCRIBE_LOG" 2>&1; then
    sed -n '1,200p' "$TRANSCRIBE_LOG" >&2
    fail "Current Brooklyn Bridge Park schedule transcription verification failed."
  fi

  candidate_image_path="$(
    jq -er '.imagePath | strings | select(length > 0)' "$candidate_source"
  )"
  require_repo_data_path "$candidate_image_path" "Verified Brooklyn Bridge Park imagePath"
  staged_image="$STAGED_DIR/$candidate_image_path"
  mkdir -p "$(dirname "$staged_image")"
  cp "$CURRENT_IMAGE_ABS" "$staged_image"

  if ! run_generator \
    "--validate-bbp-source=$candidate_source --bbp-image-root=$STAGED_DIR" \
    >"$candidate_canonical" 2>>"$VALIDATION_LOG"; then
    fail "The newly transcribed Brooklyn Bridge Park source did not validate."
  fi

  jq -S '{validFrom, validTo, blocks}' "$current_canonical" >"$current_semantics"
  jq -S '{validFrom, validTo, blocks}' "$candidate_canonical" >"$candidate_semantics"
  if ! cmp -s "$current_semantics" "$candidate_semantics"; then
    diff -u "$current_semantics" "$candidate_semantics" >"$verify_diff" || true
    fail "The checked-in Brooklyn Bridge Park transcription differs from two fresh reads."
  fi
  : >"$verify_diff"
  jq -n \
    --arg sourcePageUrl "$CURRENT_SOURCE_PAGE_URL" \
    --arg imageUrl "$CURRENT_IMAGE_URL" \
    --arg imageSha256 "$CURRENT_SHA256" \
    --arg result "verified" \
    '{
      sourcePageUrl: $sourcePageUrl,
      imageUrl: $imageUrl,
      imageSha256: $imageSha256,
      result: $result
    }' >"$SUMMARY_FILE"

  echo "The checked-in Brooklyn Bridge Park transcription matches two fresh reads."
}

all_phases() {
  OPENAI_API_KEY= fetch_phase
  if [[ "$(jq -er '.change' "$METADATA_FILE")" == "content" ]]; then
    KERNEL_API_KEY= transcribe_phase
  fi
  KERNEL_API_KEY= OPENAI_API_KEY= prepare_phase
}

usage() {
  printf '%s\n' \
    "Usage: scripts/update-bbp-schedule.sh [fetch|transcribe|prepare|all|verify-current]" \
    "" \
    "  fetch           Fetch and compare through one Kernel browser session." \
    "  transcribe      Run the two-pass transcription after a content change." \
    "  prepare         Validate, stage, and install data/bbp changes." \
    "  all             Run all required phases. This is the default." \
    "  verify-current  Re-read the checked-in image twice and require an exact match."
}

main() {
  case "${1:-all}" in
    fetch) OPENAI_API_KEY= fetch_phase ;;
    transcribe) KERNEL_API_KEY= transcribe_phase ;;
    prepare) KERNEL_API_KEY= OPENAI_API_KEY= prepare_phase ;;
    all) all_phases ;;
    verify-current) KERNEL_API_KEY= verify_current_phase ;;
    -h | --help) usage ;;
    *)
      usage >&2
      return 2
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
