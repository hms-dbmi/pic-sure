#!/usr/bin/env bash
# Drives allow + reject cases through the observe-mode gateway so the reconciler
# sees both decisions per route. Requires: GATEWAY_URL, a VALID_TOKEN (privileged),
# a NOPRIV_TOKEN (authenticated, no privileges), and an EXPIRED_TOKEN.
set -euo pipefail
: "${GATEWAY_URL:?}"; : "${VALID_TOKEN:?}"; : "${NOPRIV_TOKEN:?}"; : "${EXPIRED_TOKEN:?}"

post() { # $1=token $2=path $3=body
  curl -sS -o /dev/null -w "%{http_code} $2\n" \
    -X POST "$GATEWAY_URL$2" \
    -H "Authorization: Bearer $1" -H "Content-Type: application/json" -d "$3"
}

echo "== allow cases =="
post "$VALID_TOKEN" "/picsure/query/sync"   '{"resourceUUID":"R","query":{"fields":[]}}'
post "$VALID_TOKEN" "/v3/query/sync"         '{"resourceUUID":"R","query":{"fields":[]}}'

echo "== reject cases =="
post "$NOPRIV_TOKEN"  "/picsure/query/sync"  '{"resourceUUID":"R","query":{"fields":[]}}'
post "$EXPIRED_TOKEN" "/picsure/query/sync"  '{"resourceUUID":"R","query":{"fields":[]}}'
post "$VALID_TOKEN"   "/picsure/query/sync"  'not-json'                  # malformed body
post "$NOPRIV_TOKEN"  "/v3/query/sync"       '{"resourceUUID":"R","query":{"fields":[]}}'

echo "== open-access allow/deny =="
post "" "/picsure/query/sync" '{"query":{"fields":["allowed_concept"]}}'
post "" "/picsure/query/sync" '{"query":{"fields":["restricted_concept"]}}'
