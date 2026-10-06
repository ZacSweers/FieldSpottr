#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run_availability_attempt() {
  local attempt="$1"
  KERNEL_DIAGNOSTICS_DIR="$ROOT/build/availability-diagnostics/attempt-$attempt" \
    "$ROOT/scripts/update-availability.sh"
}

refresh_availability_with_retry() {
  local attempt
  for attempt in 1 2; do
    echo "Availability refresh attempt $attempt of 2"
    # A separate process keeps errexit active and cleans up each browser session.
    if run_availability_attempt "$attempt"; then
      return 0
    fi
    if [[ "$attempt" -eq 1 ]]; then
      echo "Availability refresh failed; retrying with a fresh session in 15 seconds." >&2
      sleep 15
    fi
  done
  echo "Availability refresh failed after two attempts." >&2
  return 1
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  refresh_availability_with_retry
fi
