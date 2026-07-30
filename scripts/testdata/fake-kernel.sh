#!/usr/bin/env bash

set -euo pipefail

: "${FAKE_KERNEL_LOG:?}"
printf '%s\n' "$*" >>"$FAKE_KERNEL_LOG"

if [[ "${1:-}" == "--version" ]]; then
  echo "kernel ${FAKE_KERNEL_VERSION:-0.26.0}"
  exit 0
fi

if [[ "${1:-}" != "browsers" ]]; then
  exit 2
fi

case "${2:-}" in
  create)
    echo '{"session_id":"test-session","cdp_ws_url":"signed-test-url"}'
    ;;
  curl)
    : "${FAKE_KERNEL_STATE:?}"
    count=0
    if [[ -f "$FAKE_KERNEL_STATE" ]]; then
      count="$(<"$FAKE_KERNEL_STATE")"
    fi
    count=$((count + 1))
    printf '%s\n' "$count" >"$FAKE_KERNEL_STATE"

    output=""
    shift 2
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == "--output" ]]; then
        output="$2"
        break
      fi
      shift
    done
    [[ -n "$output" ]] || exit 2

    case "${FAKE_KERNEL_MODE:-raw}" in
      raw)
        printf '%s\n' '{"availability":{}}' >"$output"
        ;;
      block-then-raw)
        if [[ "$count" -eq 1 ]]; then
          printf '%s\n' '<html><title>Just a moment...</title><div class="cf-chl-test">Cloudflare Ray ID: abc</div></html>' >"$output"
        else
          printf '%s\n' '{"availability":{}}' >"$output"
        fi
        ;;
      fail)
        exit 22
        ;;
      *)
        exit 2
        ;;
    esac
    ;;
  playwright)
    ;;
  delete)
    if [[ "${FAKE_KERNEL_DELETE_FAIL:-false}" == "true" ]]; then
      exit 1
    fi
    ;;
  *)
    exit 2
    ;;
esac
