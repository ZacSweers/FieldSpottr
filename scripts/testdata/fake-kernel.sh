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
    if [[ "${FAKE_KERNEL_WARNING:-false}" == true ]]; then
      echo 'CLI warning secret-token signed-test-url' >&2
    fi
    session_id=test-session
    if [[ -n "${FAKE_KERNEL_SESSION_COUNTER:-}" ]]; then
      session_count=0
      if [[ -f "$FAKE_KERNEL_SESSION_COUNTER" ]]; then
        session_count="$(<"$FAKE_KERNEL_SESSION_COUNTER")"
      fi
      session_count=$((session_count + 1))
      printf '%s\n' "$session_count" >"$FAKE_KERNEL_SESSION_COUNTER"
      session_id="test-session-$session_count"
    fi
    printf '{"session_id":"%s","cdp_ws_url":"signed-test-url"}\n' "$session_id"
    ;;
  curl)
    : "${FAKE_KERNEL_STATE:?}"
    url="${4:-}"
    session_id="${3:-}"
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
      fail-first-session)
        if [[ "$session_id" == test-session-1 ]]; then
          printf '%s\n' 'secret-token secret-cookie signed-test-url' >"$output"
          printf '%s\n' 'kernel_http_status=503' 'kernel_time_total=0.5'
          echo 'HTTP error: 503; Set-Cookie: secret-cookie; Authorization: Bearer secret-token' >&2
          exit 22
        fi
        printf '%s\n' '{"availability":{}}' >"$output"
        ;;
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
      binary)
        : "${FAKE_KERNEL_BINARY_FILE:?}"
        cp "$FAKE_KERNEL_BINARY_FILE" "$output"
        ;;
      block-then-binary)
        : "${FAKE_KERNEL_BINARY_FILE:?}"
        if [[ "$count" -eq 1 ]]; then
          printf '%s\n' '<html><title>Just a moment...</title><div class="cf-chl-test">Cloudflare Ray ID: abc</div></html>' >"$output"
        else
          cp "$FAKE_KERNEL_BINARY_FILE" "$output"
        fi
        ;;
      bbp)
        : "${FAKE_BBP_IMAGE_FILE:?}"
        : "${FAKE_BBP_IMAGE_URL:?}"
        if [[ "$url" == *"/places-to-see/pier-5/" ]]; then
          printf '<html><img src="%s"></html>\n' "$FAKE_BBP_IMAGE_URL" >"$output"
        else
          cp "$FAKE_BBP_IMAGE_FILE" "$output"
        fi
        ;;
      bbp-image-block-then-image)
        : "${FAKE_BBP_IMAGE_FILE:?}"
        : "${FAKE_BBP_IMAGE_URL:?}"
        if [[ "$url" == *"/places-to-see/pier-5/" ]]; then
          printf '<html><img src="%s"></html>\n' "$FAKE_BBP_IMAGE_URL" >"$output"
        elif [[ "$count" -eq 2 ]]; then
          printf '%s\n' '<html><title>Just a moment...</title><div class="cf-chl-test">Cloudflare Ray ID: abc</div></html>' >"$output"
        else
          cp "$FAKE_BBP_IMAGE_FILE" "$output"
        fi
        ;;
      bbp-image-fail)
        : "${FAKE_BBP_IMAGE_URL:?}"
        if [[ "$url" == *"/places-to-see/pier-5/" ]]; then
          printf '<html><img src="%s"></html>\n' "$FAKE_BBP_IMAGE_URL" >"$output"
        else
          exit 22
        fi
        ;;
      fail)
        printf '%s\n' 'secret-token secret-cookie signed-test-url' >"$output"
        printf '%s\n' 'kernel_http_status=503' 'kernel_time_total=0.5'
        echo 'HTTP error: 503; Set-Cookie: secret-cookie; Authorization: Bearer secret-token' >&2
        exit 22
        ;;
      *)
        exit 2
        ;;
    esac
    printf '%s\n' 'kernel_http_status=200' 'kernel_time_total=0.5'
    ;;
  playwright)
    if [[ "${FAKE_KERNEL_WARNING:-false}" == true ]]; then
      echo 'CLI warning secret-token signed-test-url' >&2
    fi
    if [[ "${FAKE_KERNEL_PLAYWRIGHT_SUCCESS:-true}" == true ]]; then
      echo '{"success":true,"result":{"httpStatus":200}}'
    else
      echo '{"success":false,"error":"page.goto: net::ERR_CONNECTION_RESET secret-token secret-cookie signed-test-url"}'
    fi
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
