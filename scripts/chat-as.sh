#!/usr/bin/env bash
# Sends one prompt to the running agent app as a Claude test user, then prints the JSON response
# and the HTTP status. Gets (or reuses) the user's token through workos-token.sh.
#
# Usage: scripts/chat-as.sh <1|2> "<prompt>"
# AGENT_APP_URL overrides the default http://127.0.0.1:8080.
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <1|2> \"<prompt>\"" >&2
  exit 2
fi

header_file=$("$(dirname "$0")/workos-token.sh" "$1")

jq -n --arg message "$2" '{message: $message}' |
  curl -sS -m 180 -H @"$header_file" -H 'Content-Type: application/json' -d @- \
    -w '\nhttp=%{http_code} time=%{time_total}s\n' \
    "${AGENT_APP_URL:-http://127.0.0.1:8080}/api/chat"
