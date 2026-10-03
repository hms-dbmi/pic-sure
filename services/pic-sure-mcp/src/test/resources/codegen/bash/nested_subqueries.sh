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
      "phenotypicClauses" : [
        {
          "phenotypicFilterType" : "FILTER",
          "conceptPath" : "\\phs000001\\demographics\\sex\\",
          "values" : [
            "Female",
            "Male"
          ]
        },
        {
          "phenotypicClauses" : [
            {
              "phenotypicFilterType" : "FILTER",
              "conceptPath" : "\\phs000001\\demographics\\age\\",
              "min" : 40.0,
              "max" : 65.5
            },
            {
              "phenotypicFilterType" : "REQUIRED",
              "conceptPath" : "\\phs000001\\exam\\BMI (kg/m2)\\"
            },
            {
              "phenotypicFilterType" : "ANY_RECORD_OF",
              "conceptPath" : "\\phs000002\\labs\\"
            }
          ],
          "operator" : "OR"
        },
        {
          "phenotypicFilterType" : "FILTER",
          "conceptPath" : "\\phs000002\\visit\\sex\\",
          "values" : [
            "F"
          ]
        },
        {
          "phenotypicFilterType" : "REQUIRED",
          "conceptPath" : "\\phs000003\\count\\"
        }
      ],
      "operator" : "AND"
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
