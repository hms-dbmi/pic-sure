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
    "phenotypicClause" : {
      "phenotypicFilterType" : "FILTER",
      "conceptPath" : "\\phs000007\\pht000009\\phv00000011\\SEX\\",
      "values" : [
        "Female"
      ]
    },
    "expectedResultType" : "COUNT"
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

picsure_post "${query_url}/sync" | jq -r 'if type == "number" then . else error("PIC-SURE did not return a count") end'
