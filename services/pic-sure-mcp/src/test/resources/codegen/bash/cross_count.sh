#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${PICSURE_TOKEN:-}" ]]; then
  echo "Set PICSURE_TOKEN to your PIC-SURE token before running this script." >&2
  exit 1
fi

query_url='https://picsure.example.org/picsure/hpds/auth/query'
query_file="$(mktemp)"
trap 'rm -f "$query_file"' EXIT

cat > "$query_file" <<'PICSURE_QUERY_JSON'
{
  "query" : {
    "select" : [
      "\\phs000001\\demographics\\race\\"
    ],
    "phenotypicClause" : {
      "phenotypicFilterType" : "FILTER",
      "conceptPath" : "\\phs000001\\demographics\\age\\",
      "min" : 18.0
    },
    "expectedResultType" : "CROSS_COUNT"
  }
}
PICSURE_QUERY_JSON

picsure_post() {
  curl --silent --show-error --fail \
    -H @<(printf 'Authorization: Bearer %s\n' "$PICSURE_TOKEN") \
    -H 'Content-Type: application/json' \
    --data-binary @"$query_file" \
    "$@"
}

picsure_post "${query_url}/sync" | jq -r 'to_entries[] | "\(.key) \(.value)"'
