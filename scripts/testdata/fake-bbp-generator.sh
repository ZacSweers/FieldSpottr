#!/usr/bin/env bash

set -euo pipefail

: "${FAKE_BBP_GENERATOR_LOG:?}"

generator_args=""
for argument in "$@"; do
  case "$argument" in
    --args=*)
      generator_args="${argument#--args=}"
      ;;
  esac
done
[[ -n "$generator_args" ]] || exit 2
printf '%s\n' "$generator_args" >>"$FAKE_BBP_GENERATOR_LOG"

option_value() {
  local name="$1"
  local token
  for token in $generator_args; do
    case "$token" in
      --"$name"=*)
        printf '%s\n' "${token#*=}"
        return 0
        ;;
    esac
  done
  return 1
}

case "$generator_args" in
  *--discover-bbp-image=*)
    : "${FAKE_BBP_IMAGE_URL:?}"
    printf '%s\n' "$FAKE_BBP_IMAGE_URL"
    ;;
  *--transcribe-bbp-source=*)
    : "${OPENAI_API_KEY:?}"
    if [[ "${FAKE_BBP_GENERATOR_MODE:-success}" == "transcribe-fail" ]]; then
      echo "Fake BBP transcription failure" >&2
      exit 1
    fi
    : "${FAKE_BBP_TRANSCRIBED_SOURCE:?}"
    output="$(option_value bbp-source-output)"
    diagnostics="$(option_value bbp-diagnostics-dir)"
    mkdir -p "$(dirname "$output")" "$diagnostics"
    cp "$FAKE_BBP_TRANSCRIBED_SOURCE" "$output"
    printf '%s\n' '{"extraction":1,"outcome":"success","responseId":"resp_1"}' \
      >"$diagnostics/extraction-1.json"
    printf '%s\n' '{"extraction":2,"outcome":"success","responseId":"resp_2"}' \
      >"$diagnostics/extraction-2.json"
    ;;
  *--validate-bbp-source=*)
    case "${FAKE_BBP_GENERATOR_MODE:-success}" in
      validate-fail)
        echo "Invalid BBP source: fake validation failure" >&2
        exit 1
        ;;
      validate-expired)
        echo "Invalid BBP source: candidate expired on 2026-01-01" >&2
        exit 1
        ;;
    esac
    source_file="$(option_value validate-bbp-source)"
    jq '.' "$source_file"
    ;;
  *--generate-bbp-only*)
    if [[ "${FAKE_BBP_GENERATOR_MODE:-success}" == "generate-fail" ]]; then
      echo "Fake focused generation failure" >&2
      exit 1
    fi
    output_root="$(option_value output)"
    mkdir -p "$output_root/availability/areas"
    printf '%s\n' '{"areaName":"Brooklyn Bridge Park","generatedAt":null,"rows":[]}' \
      >"$output_root/availability/areas/brooklyn-bridge-park.json"
    printf '%s\n' '{"generatedAt":null,"areas":[]}' \
      >"$output_root/availability/manifest.json"
    ;;
  *)
    exit 2
    ;;
esac
