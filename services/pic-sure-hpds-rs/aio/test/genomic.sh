#!/usr/bin/env bash
# shellcheck disable=SC2015
# Functional test of the genomic routes: standalone hpds-rs containers over migrated genomic
# stores, every genomic route over HTTP, the genomic health states, and (with HARNESS set) the
# recorded Java answers and the oracle on the genomic corpora.
#
# Usage: aio/test/genomic.sh
#
# Never touches the all-in-one: every container is named <prefix>http-<name>, publishes only on
# 127.0.0.1, mounts its stores read-only, and is removed on exit. It runs on its own, apart from
# functional.sh, because it needs the genomic bench stores.
#
# Env:
#   GENOMIC_BENCH      the bench data directory (.worktrees/bench-data), holding
#                      genomic/gfx/{pheno-rust,ld/rust,lpd/rust,ld/genomic} and
#                      genomic/g1kg/{pheno-rust,rust}; required
#   HPDS_RS_IMAGE      the image to test (hms-dbmi/pic-sure-hpds-rs:v5)
#   FUNCTIONAL_PREFIX  prefix of every container name (v5- gives v5-http-*); lowercase letters,
#                      digits and hyphens only
#   HARNESS            brain/rust-hpds-phenotype/equivalence-harness; when set, the corpora in
#                      $GENOMIC_BENCH/equivalence-genomic/corpora are replayed against each
#                      container and judged by scripts/compare_genomic.py against the recorded
#                      Java answers and the oracle; any Rust WRONG fails the run
#   PYTHON             the harness interpreter, with duckdb ($GENOMIC_BENCH/equivalence/.venv/bin/python)
#   LAYERS_gfx_ld, LAYERS_gfx_lpd, LAYERS_g1kg   the corpus layers replayed per dataset; the
#                      defaults are the layers the comparer judges with no unnamed class
set -euo pipefail

HERE="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
B="${GENOMIC_BENCH:?set GENOMIC_BENCH to the bench data directory}"
B="$(cd -P "$B" && pwd -P)"
IMAGE="${HPDS_RS_IMAGE:-hms-dbmi/pic-sure-hpds-rs:v5}"
PREFIX="${FUNCTIONAL_PREFIX:-}"
if [[ ! "$PREFIX" =~ ^[a-z0-9-]*$ ]]; then
  echo "FUNCTIONAL_PREFIX may hold only lowercase letters, digits and hyphens" >&2
  exit 2
fi
PYTHON="${PYTHON:-$B/equivalence/.venv/bin/python}"
LAYERS_gfx_ld="${LAYERS_gfx_ld:-IT G0 GF GS FP}"
LAYERS_gfx_lpd="${LAYERS_gfx_lpd:-G0 GF GS}"
LAYERS_g1kg="${LAYERS_g1kg:-GT}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/genomic-functional.XXXXXX")"
NAMES=()
failures=0

pass() { echo "PASS  $*"; }
check_failed() { echo "FAIL  $*"; failures=$((failures + 1)); }
expect_equal() {
  if [[ "$2" == "$3" ]]; then pass "$1"; else check_failed "$1 (expected '$3', got '$2')"; fi
}

cleanup() {
  for name in "${NAMES[@]}"; do docker rm -f "$name" >/dev/null 2>&1 || true; done
  rm -rf "$WORK"
}
trap cleanup EXIT

for required in "$B/genomic/gfx/pheno-rust/pheno.index" "$B/genomic/gfx/ld/rust/geno.index" \
  "$B/genomic/gfx/lpd/rust/geno.index" "$B/genomic/g1kg/pheno-rust/pheno.index" "$B/genomic/g1kg/rust/geno.index"; do
  [[ -f "$required" ]] || { echo "missing $required" >&2; exit 2; }
done

# Starts container <prefix>http-<name> on 127.0.0.1:<port> over a phenotype and a genomic store,
# with the bench's Java settings (excerpts on, consents not required, exports on, metrics on).
# Arguments: name, phenotype dir, genomic dir, hpds.genomicProcessor.impl, port, extra docker args.
start() {
  local name="${PREFIX}http-$1" pheno="$2" genomic="$3" impl="$4" port="$5"
  shift 5
  docker rm -f "$name" >/dev/null 2>&1 || true
  NAMES+=("$name")
  docker run -d --name "$name" --memory 4g -p "127.0.0.1:$port:8080" \
    -v "$pheno:/opt/local/hpds:ro" -v "$genomic:/opt/local/genomic:ro" \
    -e HPDS_GENOMIC_DATA_DIRECTORY=/opt/local/genomic/ -e "hpds.genomicProcessor.impl=$impl" \
    -e VCF_EXCERPT_ENABLED=true -e HPDS_REQUIRE_AUTHORIZATION_FILTER=false -e ID_BATCH_SIZE=1000 \
    -e HPDS_CONSENT_CONTRACT=harmonization \
    -e HPDS_METRICS=on "$@" "$IMAGE" >/dev/null
  local waited=0
  until curl -s -o /dev/null "http://127.0.0.1:$port/actuator/health" || [[ "$waited" -ge 120 ]]; do
    sleep 0.5
    waited=$((waited + 1))
  done
}

health_of() { curl -s "http://127.0.0.1:$1/actuator/health"; }

sync() {
  curl -s -D "$WORK/headers" -o "$WORK/body" -w '%{http_code}' -H 'Content-Type: application/json' \
    --data "{\"query\":$2}" "http://127.0.0.1:$1/PIC-SURE/v3/query/sync"
}

# Checks every genomic route of the container on <port> over the column <column>, whose value
# <value> some variant carries.
routes() {
  local what="$1" port="$2" column="$3" value="$4" status
  expect_equal "$what: health" "$(health_of "$port")" '{"status":"UP"}'
  local filter="[{\"key\":\"$column\",\"values\":[\"$value\"]}]"
  status="$(sync "$port" '{"expectedResultType":"INFO_COLUMN_LISTING"}')"
  [[ "$status" == 200 && "$(jq 'length' "$WORK/body")" -gt 0 ]] && pass "$what: INFO_COLUMN_LISTING lists columns" \
    || check_failed "$what: INFO_COLUMN_LISTING ($status)"
  status="$(sync "$port" "{\"expectedResultType\":\"COUNT\",\"genomicFilters\":$filter}")"
  [[ "$status" == 200 && "$(cat "$WORK/body")" =~ ^[1-9][0-9]*$ ]] && pass "$what: COUNT with a genomic filter ($(cat "$WORK/body"))" \
    || check_failed "$what: COUNT with a genomic filter ($status $(head -c 200 "$WORK/body"))"
  status="$(sync "$port" "{\"expectedResultType\":\"VARIANT_COUNT_FOR_QUERY\",\"genomicFilters\":$filter}")"
  [[ "$status" == 200 && "$(jq -r '.message' "$WORK/body")" == "Query ran successfully" ]] && pass "$what: VARIANT_COUNT_FOR_QUERY ($(jq -c . "$WORK/body"))" \
    || check_failed "$what: VARIANT_COUNT_FOR_QUERY ($status)"
  status="$(sync "$port" "{\"expectedResultType\":\"VARIANT_LIST_FOR_QUERY\",\"genomicFilters\":$filter}")"
  [[ "$status" == 200 && "$(head -c 1 "$WORK/body")" == "[" ]] && pass "$what: VARIANT_LIST_FOR_QUERY" \
    || check_failed "$what: VARIANT_LIST_FOR_QUERY ($status)"
  for type in VCF_EXCERPT AGGREGATE_VCF_EXCERPT; do
    status="$(sync "$port" "{\"expectedResultType\":\"$type\",\"genomicFilters\":$filter}")"
    local length bytes
    length="$(tr -d '\r' < "$WORK/headers" | sed -n 's/^content-length: //Ip')"
    bytes="$(wc -c < "$WORK/body" | tr -d ' ')"
    [[ "$status" == 200 && "$length" == "$bytes" && "$(head -c 5 "$WORK/body")" == "CHROM" ]] \
      && pass "$what: $type streams its $bytes bytes" || check_failed "$what: $type ($status, length '$length', $bytes bytes)"
  done
  status="$(curl -s -o "$WORK/body" -w '%{http_code}' -H 'Content-Type: application/json' \
    --data '{"query":{"expectedResultType":"AGGREGATE_VCF_EXCERPT"}}' "http://127.0.0.1:$port/PIC-SURE/v3/query")"
  expect_equal "$what: a genomic type on POST /query is refused" "$status $(cat "$WORK/body")" \
    "400 result type AGGREGATE_VCF_EXCERPT is not supported by POST /query; send it to POST /query/sync"
  status="$(curl -s -o "$WORK/body" -w '%{http_code}' -H 'Content-Type: application/json' \
    --data "{\"query\":\"$column\"}" "http://127.0.0.1:$port/PIC-SURE/v3/search")"
  [[ "$status" == 200 && "$(jq --arg c "$column" '.results.info[$c].values | length' "$WORK/body")" -gt 0 ]] \
    && pass "$what: /search lists $column values" || check_failed "$what: /search info ($status)"
  status="$(curl -s -o "$WORK/body" -w '%{http_code}' \
    "http://127.0.0.1:$port/PIC-SURE/v3/search/values/?genomicConceptPath=$column&query=&page=1&size=5")"
  [[ "$status" == 200 && "$(jq '.results | length' "$WORK/body")" -gt 0 && "$(jq '.page' "$WORK/body")" == 1 ]] \
    && pass "$what: /search/values pages ($(jq '.total' "$WORK/body") values)" || check_failed "$what: /search/values ($status)"
  status="$(curl -s -o "$WORK/body" -w '%{http_code}' \
    "http://127.0.0.1:$port/PIC-SURE/v3/search/values/?genomicConceptPath=No_such_column&query=&page=1&size=5")"
  expect_equal "$what: /search/values of an unknown column is refused" "$status" 400
  curl -s "http://127.0.0.1:$port/metrics" > "$WORK/metrics"
  grep -qx 'hpds_genomic_store_up 1' "$WORK/metrics" && grep -q '^hpds_genomic_cache_loads_total ' "$WORK/metrics" \
    && grep -q '^hpds_genomic_read_budget_bytes ' "$WORK/metrics" && grep -qx 'hpds_excerpts_in_progress 0' "$WORK/metrics" \
    && grep -q '^hpds_excerpts_refused_total ' "$WORK/metrics" && pass "$what: /metrics reports the genomic cache and budget and the excerpt slots" \
    || check_failed "$what: /metrics genomic lines"
  local container="${PREFIX}http-$what"
  [[ -n "$(docker exec "$container" sh -c 'touch /opt/local/genomic/x' 2>&1 || true)" ]] \
    && pass "$what: genomic mount is read-only and never written" || check_failed "$what: genomic mount is read-only"
}

# Replays the corpus layers <layers> of dataset <dataset> against the container on <port> and
# judges them with the comparer.
corpora() {
  local dataset="$1" port="$2" layers="$3" root out
  [[ -n "${HARNESS:-}" ]] || return 0
  root="$(cd -P "$B/../.." && pwd -P)"
  out="$B/equivalence-genomic/out/$dataset"
  for layer in $layers; do
    local corpus="$B/equivalence-genomic/corpora/$dataset/$layer" java="$out/$layer-java.jsonl.gz"
    [[ -f "$java" ]] || { check_failed "$dataset $layer: no recorded Java answers"; continue; }
    (cd "$root" && "$PYTHON" -I "$HARNESS/genomic/run_corpus.py" "http://127.0.0.1:$port/PIC-SURE" "$corpus" \
      "$WORK/$dataset-$layer-rs.jsonl.gz" > "$WORK/$dataset-$layer-run.log" 2>&1) \
      || { check_failed "$dataset $layer: replay"; tail -5 "$WORK/$dataset-$layer-run.log"; continue; }
    if (cd "$root" && "$PYTHON" -I "$HARNESS/scripts/compare_genomic.py" "$HARNESS/genomic/datasets/$dataset.json" \
      "$B/equivalence-genomic/oracle" "$corpus" "$java" "$WORK/$dataset-$layer-rs.jsonl.gz" \
      "$WORK/$dataset-$layer-cmp.jsonl.gz" > "$WORK/$dataset-$layer-cmp.log" 2>&1); then
      pass "$dataset $layer: Rust over HTTP equals Java or the oracle ($(grep -E '^ *"(same|problems)"' "$WORK/$dataset-$layer-cmp.log" | tr -d ' \n'))"
    else
      check_failed "$dataset $layer: comparison"; tail -15 "$WORK/$dataset-$layer-cmp.log"
    fi
  done
}

G="$B/genomic"
echo "--- gfx, localDistributed"
start gfx-ld "$G/gfx/pheno-rust" "$G/gfx/ld/rust" localDistributed 28941
routes gfx-ld 28941 Gene_with_variant LOC102723996
corpora gfx-ld 28941 "$LAYERS_gfx_ld"

echo "--- gfx, localPatientDistributed"
start gfx-lpd "$G/gfx/pheno-rust" "$G/gfx/lpd/rust" localPatientDistributed 28942
routes gfx-lpd 28942 Gene_with_variant LOC102723996
corpora gfx-lpd 28942 "$LAYERS_gfx_lpd"

echo "--- g1kg, localPatientDistributed"
start g1kg "$G/g1kg/pheno-rust" "$G/g1kg/rust" localPatientDistributed 28943
routes g1kg 28943 Gene_with_variant SRM
corpora g1kg 28943 "$LAYERS_g1kg"

echo "--- genomic health DOWN reasons"
down_check() {
  local what="$1" port="$2" reason="$3" body
  body="$(health_of "$port")"
  expect_equal "$what: health" "$body" "{\"status\":\"DOWN\",\"reason\":\"$reason\"}"
  expect_equal "$what: genomic COUNT is a 503" "$(sync "$port" '{"expectedResultType":"COUNT","genomicFilters":[{"key":"Gene_with_variant","values":["LOC102723996"]}]}')" 503
  expect_equal "$what: phenotype COUNT still answers" "$(sync "$port" '{"expectedResultType":"COUNT"}') $(cat "$WORK/body")" "200 4978"
}
start down-layout "$G/gfx/pheno-rust" "$G/gfx/ld/rust" localPatientDistributed 28944
down_check down-layout 28944 "the genomic store's layout is not the one hpds.genomicProcessor.impl names; see the server log"
docker rm -f "${PREFIX}http-down-layout" >/dev/null
start down-java "$G/gfx/pheno-rust" "$G/gfx/ld/genomic" localDistributed 28945
down_check down-java 28945 "the genomic store could not be opened; see the server log"
[[ "$(docker logs "${PREFIX}http-down-java" 2>&1)" == *"hpds-etl genomic-migrate"* ]] \
  && pass "down-java: the log names hpds-etl genomic-migrate" || check_failed "down-java: the log names hpds-etl genomic-migrate"
docker rm -f "${PREFIX}http-down-java" >/dev/null
mkdir -p "$WORK/empty"
start down-missing "$G/gfx/pheno-rust" "$WORK/empty" localDistributed 28946
down_check down-missing 28946 "the genomic store could not be opened; see the server log"
docker rm -f "${PREFIX}http-down-missing" >/dev/null
start unset "$G/gfx/pheno-rust" "$G/gfx/ld/rust" "" 28947
expect_equal "unset impl: health UP without genomic data" "$(health_of 28947)" '{"status":"UP"}'
expect_equal "unset impl: INFO_COLUMN_LISTING is []" "$(sync 28947 '{"expectedResultType":"INFO_COLUMN_LISTING"}') $(cat "$WORK/body")" "200 []"

echo
if [[ "$failures" -eq 0 ]]; then echo "all genomic checks passed"; else echo "$failures genomic check(s) failed"; exit 1; fi
