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
      "\\phs000001\\exam\\height\\",
      "\\phs000001\\exam\\weight\\"
    ],
    "phenotypicClause" : {
      "phenotypicClauses" : [
        {
          "phenotypicFilterType" : "FILTER",
          "conceptPath" : "\\phs000001\\site\\",
          "values" : [
            "Caf\u00E9 \"North\""
          ]
        },
        {
          "phenotypicFilterType" : "FILTER",
          "conceptPath" : "\\phs000001\\demographics\\age\\",
          "max" : 0.5
        }
      ],
      "operator" : "AND"
    },
    "expectedResultType" : "DATAFRAME"
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

query_id="$(picsure_post "$query_url" | jq -r '.picsureResultId // empty')"
if [[ ! "$query_id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  echo "PIC-SURE did not return a query id." >&2
  exit 1
fi

query_status() {
  picsure_post "${query_url}/${query_id}/status" | jq -r '(.status // .resourceStatus // "") | ascii_upcase'
}

max_checks=25
checks=1
delay=2
status="$(query_status)"
while [[ "$status" != "AVAILABLE" ]]; do
  if [[ "$status" == "ERROR" ]]; then
    echo "PIC-SURE reported an error for query ${query_id}." >&2
    exit 1
  fi
  if (( checks >= max_checks )); then
    echo "Query ${query_id} did not finish after ${max_checks} status checks." >&2
    exit 1
  fi
  sleep "$delay"
  delay=$(( delay * 2 > 30 ? 30 : delay * 2 ))
  checks=$(( checks + 1 ))
  status="$(query_status)"
done

mkdir -p picsure_results
output_path='picsure_results/participant.csv'
picsure_post "${query_url}/${query_id}/result" --output "$output_path"
line_count="$(wc -l < "$output_path" | tr -d ' ')"
printf 'Saved %s lines to %s\n' "$line_count" "$output_path"
