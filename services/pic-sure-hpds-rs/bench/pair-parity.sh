#!/usr/bin/env bash
# Serves two hpds-rs builds natively, each on its own store, and checks that they answer every
# corpus identically: hpds-bench compare on every entry, async-compare.py on the async entries
# (status, result headers and body bytes) and search-encoding.py on the search entries.
#
# Usage: bench/pair-parity.sh <out-dir> <corpus-dir>...
#
# Env:
#   A_BIN, A_STORE      the reference build and its store (required)
#   B_BIN, B_STORE      the build under test and its store (required)
#   A_ENV, B_ENV        extra environment for one side, as space-separated NAME=value words
#   BENCH               hpds-bench binary (hpds-bench on PATH)
#   PORT_BASE           A listens on PORT_BASE+1, B on PORT_BASE+2 (18900)
#   PASSES              compare passes per corpus, for result cache runs (1)
#   RESULT_CACHE_BYTES  HPDS_RESULT_CACHE_BYTES for both sides (0)
#   CACHE_BYTES         HPDS_CACHE_BYTES for both sides (4294967296)
#   REQUIRE_CONSENTS    HPDS_REQUIRE_AUTHORIZATION_FILTER for both sides (false)
#   SEARCH_GZIP         what search-encoding.py expects of B: off (the default) or on. It does
#                       not set HPDS_SEARCH_GZIP; put that in B_ENV
#   GOLDEN              golden files, space-separated, each checked against both sides
#
# An entry that agreed only after a retry is counted as flaky on its compare line: a
# nondeterministic answer is a finding even when the retry matched.
#
# A side whose health answers DOWN (a store it refuses) ends the run at once with status 1.
#
# Writes compare-<corpus>-p<pass>.txt, async-<corpus>-p<pass>.txt, search-<corpus>.txt, both
# service logs and, when B serves /metrics, metrics-b.txt into <out-dir>. Prints one line per
# check with its exit status, then the number of WARN and ERROR lines in B's log (colour
# codes stripped first, since the service colours its level names). Both
# services are stopped on exit. Result directories live under <out-dir>.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ $# -ge 2 ]] || { sed -n '2,/^set -uo/{/^set -uo/d;s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"; exit 2; }
: "${A_BIN:?A_BIN is required}" "${A_STORE:?A_STORE is required}" "${B_BIN:?B_BIN is required}" "${B_STORE:?B_STORE is required}"
out=$1; shift
mkdir -p "$out"
bench=${BENCH:-hpds-bench}
base=${PORT_BASE:-18900}
passes=${PASSES:-1}
port_a=$((base + 1)); port_b=$((base + 2))
pids=()
stop_all() { [[ ${#pids[@]} -gt 0 ]] && kill "${pids[@]}" 2>/dev/null; wait 2>/dev/null; }
trap stop_all EXIT

serve() {
  local bin=$1 store=$2 port=$3 log=$4 extra=$5
  local results="$out/results-$port"
  rm -rf "$results"; mkdir -m 700 -p "$results"
  # shellcheck disable=SC2086
  env $extra HPDS_DATA_DIRECTORY="$store" SERVER_PORT="$port" HPDS_CACHE_BYTES="${CACHE_BYTES:-4294967296}" \
    HPDS_REQUIRE_AUTHORIZATION_FILTER="${REQUIRE_CONSENTS:-false}" HPDS_RESULTS_DIRECTORY="$results" \
    SMALL_JOB_LIMIT=100 SMALL_TASK_THREADS=4 LARGE_TASK_THREADS=2 ID_BATCH_SIZE=1000 \
    HPDS_RESULT_CACHE_BYTES="${RESULT_CACHE_BYTES:-0}" RUST_LOG=info,hpds_rs::access=warn \
    "$bin" > "$log" 2>&1 &
  pids+=($!)
}

wait_up() {
  local port=$1 i health
  for i in $(seq 1 2400); do
    health=$(curl -s "127.0.0.1:$port/actuator/health" 2>/dev/null)
    [[ "$health" == *'"UP"'* || "$health" == *'"DOWN"'* ]] && break
    sleep 0.25
  done
  echo "port $port health: $health"
  [[ "$health" == *'"UP"'* ]] || { echo "port $port is not UP; see the service logs in $out" >&2; exit 1; }
}

serve "$A_BIN" "$A_STORE" "$port_a" "$out/serve-a.log" "${A_ENV:-}"
serve "$B_BIN" "$B_STORE" "$port_b" "$out/serve-b.log" "${B_ENV:-}"
wait_up "$port_a"; wait_up "$port_b"
url_a="http://127.0.0.1:$port_a/PIC-SURE"; url_b="http://127.0.0.1:$port_b/PIC-SURE"

for corpus in "$@"; do
  c=$(basename "$corpus")
  for p in $(seq 1 "$passes"); do
    "$bench" compare --a "$url_a" --b "$url_b" --corpus "$corpus" --concurrency 4 --retries 1 > "$out/compare-$c-p$p.txt" 2>&1
    echo "compare $c pass $p exit=$? $(grep -E '^compare:' "$out/compare-$c-p$p.txt" | tr '\n' ' ')flaky: $(grep -c '^FLAKY' "$out/compare-$c-p$p.txt")"
    if grep -lq '"kind": *"async"' "$corpus"/*.json 2>/dev/null; then
      python3 -I "$HERE/async-compare.py" "$port_a" "$port_b" "$corpus" > "$out/async-$c-p$p.txt" 2>&1
      echo "async $c pass $p exit=$? $(head -1 "$out/async-$c-p$p.txt")"
    fi
  done
  if grep -lq '"kind": *"search"' "$corpus"/*.json 2>/dev/null; then
    python3 -I "$HERE/search-encoding.py" --gzip "${SEARCH_GZIP:-off}" "$port_a" "$port_b" "$corpus" > "$out/search-$c.txt" 2>&1
    echo "search $c exit=$? $(tail -1 "$out/search-$c.txt")"
  fi
done

for golden in ${GOLDEN:-}; do
  g=$(basename "$golden" .json)
  "$bench" compare --a "$url_a" --golden "$golden" > "$out/golden-$g-a.txt" 2>&1
  echo "golden $g A exit=$? $(tail -1 "$out/golden-$g-a.txt")"
  "$bench" compare --a "$url_b" --golden "$golden" > "$out/golden-$g-b.txt" 2>&1
  echo "golden $g B exit=$? $(tail -1 "$out/golden-$g-b.txt")"
done

if curl -sf "127.0.0.1:$port_b/metrics" > "$out/metrics-b.txt" 2>/dev/null; then
  echo "metrics B: $(grep -c '^hpds_' "$out/metrics-b.txt") samples"
else
  rm -f "$out/metrics-b.txt"
fi
stop_all
pids=()
echo "B log WARN and ERROR lines: $(sed $'s/\x1b\\[[0-9;]*m//g' "$out/serve-b.log" | grep -cE ' (ERROR|WARN) ')"
