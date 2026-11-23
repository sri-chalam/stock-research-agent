#!/usr/bin/env bash
# Signs in a Claude test user through the WorkOS password grant and prints the path of a curl
# header file ("Authorization: Bearer <token>"). The token itself is never printed.
# A saved token is reused while it has more than 5 minutes left.
#
# Usage: scripts/workos-token.sh <1|2>
#   curl -H @"$(scripts/workos-token.sh 1)" ...
#
# Reads from the environment: WORKOS_CLIENT_ID, WORKOS_API_KEY,
# CLAUDE_TEST_USER_<n>_EMAIL and CLAUDE_TEST_USER_<n>_PASSWORD.
set -euo pipefail

user_number="${1:-}"
case "$user_number" in
  1 | 2) ;;
  *)
    echo "usage: $0 <1|2>" >&2
    exit 2
    ;;
esac

email_var="CLAUDE_TEST_USER_${user_number}_EMAIL"
password_var="CLAUDE_TEST_USER_${user_number}_PASSWORD"
for required_var in WORKOS_CLIENT_ID WORKOS_API_KEY "$email_var" "$password_var"; do
  if [ -z "${!required_var:-}" ]; then
    echo "$required_var is not set" >&2
    exit 1
  fi
done

token_dir="$(cd "$(dirname "$0")/.." && pwd)/.tokens"
header_file="$token_dir/user$user_number.header"
umask 077
mkdir -p "$token_dir"

# Prints the saved token's exp claim, or 0 when there is no readable token.
saved_token_expiry() {
  local payload
  payload=$(sed 's/^Authorization: Bearer //' "$header_file" | cut -d. -f2 | tr '_-' '/+')
  while [ $((${#payload} % 4)) -ne 0 ]; do payload="$payload="; done
  printf '%s' "$payload" | base64 -d 2>/dev/null | jq -r '.exp // 0' 2>/dev/null || echo 0
}

if [ -s "$header_file" ] && [ "$(saved_token_expiry)" -gt $(($(date +%s) + 300)) ]; then
  echo "$header_file"
  exit 0
fi

# The request goes through stdin, so the password and API key never appear in the process list.
response=$(
  jq -n \
    --arg client_id "$WORKOS_CLIENT_ID" \
    --arg client_secret "$WORKOS_API_KEY" \
    --arg email "${!email_var}" \
    --arg password "${!password_var}" \
    '{client_id: $client_id, client_secret: $client_secret, grant_type: "password",
      email: $email, password: $password}' |
    curl -sS -m 30 -H 'Content-Type: application/json' -d @- -w '\n%{http_code}' \
      https://api.workos.com/user_management/authenticate
)
http_status="${response##*$'\n'}"
response_body="${response%$'\n'*}"

if [ "$http_status" != "200" ]; then
  echo "WorkOS sign-in failed for test user $user_number (HTTP $http_status):" >&2
  printf '%s' "$response_body" | jq -c '{error, code, message, error_description}' >&2 ||
    echo "(response was not JSON)" >&2
  exit 1
fi

printf 'Authorization: Bearer %s\n' "$(printf '%s' "$response_body" | jq -r .access_token)" \
  >"$header_file"
echo "$header_file"
