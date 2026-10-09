#!/usr/bin/env bash
# Java HPDS baseline: ETL timing and a cold/warm load matrix with async-profiler attached.
#
# Usage:
#   bench/baseline.sh etl <data-dir>
#       Runs the Java ETL (CSVLoaderNewSearch by default) on <data-dir>/allConcepts.csv, which
#       must be in concept order. Records wall time, peak container memory and store size.
#   bench/baseline.sh run <label> <data-dir> <corpus-dir>
#       For each mode (sync, all) and concurrency (1, 8, 32): a cold run on a fresh container
#       after dropping the Docker VM page cache, then a warm run after a warmup pass. Each run
#       records hpds-bench load JSON, peak container memory, GC time and a CPU plus allocation
#       profile (JFR, collapsed stacks, flame graph HTML).
#
# Env: RESULTS_DIR (default bench/results/<date>/<label>), DURATION (60s), WARMUP (30s),
#      CONCURRENCIES ("1 8 32"), MODES ("sync all"), PROFILE_EVENT (cpu, or itimer when perf
#      events are unavailable), ALLOC_INTERVAL (512k), EXCLUDE (result types left out, default
#      OBSERVATION_CROSS_COUNT, whose Java handler can loop forever serializing a corrupted
#      TreeMap even at concurrency 1; set EXCLUDE= to include it), BENCH_BIN, HOST_PORT
#      (18090), plus the docker-compose.yml variables (JAVA_OPTS, CACHE_SIZE, ETL_HEAPSIZE, ...).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH_BIN="${BENCH_BIN:-$HERE/../target/release/hpds-bench}"
HOST_PORT="${HOST_PORT:-18090}"
DURATION="${DURATION:-60s}"
WARMUP="${WARMUP:-30s}"
CONCURRENCIES="${CONCURRENCIES:-1 8 32}"
MODES="${MODES:-sync all}"
PROFILE_EVENT="${PROFILE_EVENT:-cpu}"
ALLOC_INTERVAL="${ALLOC_INTERVAL:-512k}"
EXCLUDE="${EXCLUDE-OBSERVATION_CROSS_COUNT}"
SERVICE=bench-hpds-java
ASPROF=/opt/async-profiler/bin/asprof
JFRCONV=/opt/async-profiler/bin/jfrconv
export HOST_PORT

compose() {
  docker compose -f "$HERE/docker-compose.yml" "$@"
}

log() {
  printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" >&2
}

to_mib() {
  awk '{
    v = $1; u = v; sub(/[0-9.]+/, "", u); sub(/[A-Za-z]+$/, "", v);
    if (u == "GiB") v *= 1024; else if (u == "KiB" || u == "kB") v /= 1024; else if (u == "B") v /= 1048576;
    printf "%.0f\n", v
  }'
}

sample_memory() {
  local container="$1" out="$2"
  : > "$out"
  while true; do
    docker stats --no-stream --format '{{.MemUsage}}' "$container" 2>/dev/null \
      | awk '{print $1}' | to_mib >> "$out" || true
    sleep 1
  done
}

peak_of() {
  sort -n "$1" | tail -1
}

drop_vm_page_cache() {
  docker run --rm --name bench-drop-caches --privileged alpine:3.20 \
    sh -c 'sync; echo 3 > /proc/sys/vm/drop_caches'
}

gc_seconds() {
  docker exec "$SERVICE" jstat -gcutil 1 | awk 'NR == 2 {print $NF}'
}

start_fresh() {
  local out="$1"
  compose rm -sf "$SERVICE" >/dev/null 2>&1 || true
  drop_vm_page_cache
  local t0 t1
  t0=$(python3 -c 'import time; print(time.time())')
  compose up -d "$SERVICE" >/dev/null 2>&1
  for _ in $(seq 1 1200); do
    if curl -fsS "http://127.0.0.1:$HOST_PORT/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      t1=$(python3 -c 'import time; print(time.time())')
      awk -v a="$t0" -v b="$t1" 'BEGIN {printf "%.1f\n", b - a}' > "$out"
      log "health UP after $(cat "$out")s"
      return 0
    fi
    sleep 0.5
  done
  docker logs --tail 40 "$SERVICE" >&2
  return 1
}

load_args() {
  local mode="$1" concurrency="$2"
  local args=(--url "http://127.0.0.1:$HOST_PORT/PIC-SURE" --concurrency "$concurrency")
  [[ "$mode" == "sync" ]] && args+=(--only sync)
  [[ -n "$EXCLUDE" ]] && args+=(--exclude "$EXCLUDE")
  printf '%s\n' "${args[@]}"
}

profile_and_load() {
  local name="$1" mode="$2" concurrency="$3" corpus="$4" dir="$5"
  local seconds="${DURATION%s}"
  local args=()
  while IFS= read -r a; do args+=("$a"); done < <(load_args "$mode" "$concurrency")
  local gc_before gc_after sampler
  gc_before=$(gc_seconds)
  sample_memory "$SERVICE" "$dir/$name.mem" &
  sampler=$!
  docker exec -d "$SERVICE" "$ASPROF" -d "$seconds" -e "$PROFILE_EVENT" --alloc "$ALLOC_INTERVAL" \
    -f "/results/$name.jfr" 1
  "$BENCH_BIN" load "${args[@]}" --corpus "$corpus" --duration "$DURATION" > "$dir/$name.json"
  kill "$sampler" 2>/dev/null || true
  wait "$sampler" 2>/dev/null || true
  gc_after=$(gc_seconds)
  for _ in $(seq 1 60); do
    docker exec "$SERVICE" sh -c "test -s /results/$name.jfr && ! $ASPROF status 1 | grep -q 'is running'" \
      && break
    sleep 1
  done
  docker exec "$SERVICE" "$JFRCONV" --cpu -o collapsed "/results/$name.jfr" "/results/$name.cpu.collapsed" >/dev/null
  docker exec "$SERVICE" "$JFRCONV" --cpu -o html "/results/$name.jfr" "/results/$name.cpu.html" >/dev/null
  docker exec "$SERVICE" "$JFRCONV" --alloc --total -o collapsed "/results/$name.jfr" "/results/$name.alloc.collapsed" >/dev/null
  docker exec "$SERVICE" "$JFRCONV" --alloc --total -o html "/results/$name.jfr" "/results/$name.alloc.html" >/dev/null
  local peak gc host_load
  host_load=$(sysctl -n vm.loadavg 2>/dev/null | awk '{print $2}')
  peak=$(peak_of "$dir/$name.mem")
  gc=$(awk -v a="$gc_before" -v b="$gc_after" 'BEGIN {printf "%.2f", b - a}')
  jq -r --arg n "$name" --arg peak "$peak" --arg gc "$gc" --arg load "$host_load" \
    '[$n, .overall.requests, .overall.errors, (.overall.throughput_rps * 100 | round / 100),
      .overall.p50_ms, .overall.p90_ms, .overall.p99_ms, .overall.max_ms, $peak, $gc, $load] | @tsv' \
    "$dir/$name.json" >> "$dir/summary.tsv"
  log "$(tail -1 "$dir/summary.tsv")"
}

run_matrix() {
  local label="$1" data="$2" corpus="$3"
  local dir="${RESULTS_DIR:-$HERE/results/$(date +%F)/$label}"
  mkdir -p "$dir"
  chmod 777 "$dir"
  export HPDS_DATA_DIR="$data" RESULTS_DIR="$dir"
  printf 'run\trequests\terrors\trps\tp50_ms\tp90_ms\tp99_ms\tmax_ms\tpeak_mem_mib\tgc_s\thost_load1\n' > "$dir/summary.tsv"
  du -sk "$data"/allObservationsStore.javabin "$data"/columnMeta.javabin > "$dir/store-size.txt"
  {
    echo "date=$(date -u +%FT%TZ)"
    echo "image=$(docker image inspect -f '{{.Id}}' "${JAVA_HPDS_IMAGE:-hms-dbmi/pic-sure-hpds:bench}")"
    echo "java_opts=${JAVA_OPTS:--Xmx16g -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints}"
    echo "cache_size=${CACHE_SIZE:-1500}"
    echo "exclude=$EXCLUDE"
    echo "duration=$DURATION warmup=$WARMUP profile_event=$PROFILE_EVENT alloc_interval=$ALLOC_INTERVAL"
    echo "data=$data corpus=$corpus"
  } > "$dir/settings.txt"
  local mode concurrency args
  for mode in $MODES; do
    for concurrency in $CONCURRENCIES; do
      log "$label $mode c=$concurrency cold"
      start_fresh "$dir/$mode-c$concurrency.startup_s"
      profile_and_load "$mode-c$concurrency-cold" "$mode" "$concurrency" "$corpus" "$dir"
      log "$label $mode c=$concurrency warmup"
      args=()
      while IFS= read -r a; do args+=("$a"); done < <(load_args "$mode" "$concurrency")
      "$BENCH_BIN" load "${args[@]}" --corpus "$corpus" --duration "$WARMUP" > "$dir/$mode-c$concurrency-warmup.json"
      log "$label $mode c=$concurrency warm"
      profile_and_load "$mode-c$concurrency-warm" "$mode" "$concurrency" "$corpus" "$dir"
    done
  done
  compose rm -sf "$SERVICE" >/dev/null 2>&1 || true
  column -t "$dir/summary.tsv"
}

run_etl() {
  local data="$1"
  local dir="${RESULTS_DIR:-$HERE/results/$(date +%F)/etl-$(basename "$data")}"
  mkdir -p "$dir"
  export HPDS_DATA_DIR="$data"
  [[ -s "$data/allConcepts.csv" ]] || { echo "missing $data/allConcepts.csv" >&2; exit 1; }
  [[ -s "$data/encryption_key" ]] || openssl rand -hex 16 | tr -d '\n' > "$data/encryption_key"
  chmod 777 "$data"
  docker rm -f bench-hpds-java-etl >/dev/null 2>&1 || true
  local t0 t1 sampler status=0
  t0=$(date +%s)
  sample_memory bench-hpds-java-etl "$dir/etl.mem" &
  sampler=$!
  compose --profile etl run --rm --name bench-hpds-java-etl bench-hpds-java-etl > "$dir/etl.log" 2>&1 || status=$?
  t1=$(date +%s)
  kill "$sampler" 2>/dev/null || true
  wait "$sampler" 2>/dev/null || true
  {
    echo "exit_status=$status"
    echo "wall_s=$((t1 - t0))"
    echo "peak_mem_mib=$(peak_of "$dir/etl.mem")"
    echo "heap_mb=${ETL_HEAPSIZE:-16384}"
    du -sk "$data/allConcepts.csv" "$data/allObservationsStore.javabin" "$data/columnMeta.javabin" 2>/dev/null
  } | tee "$dir/etl.txt"
  if [[ ! -s "$data/columnMeta.csv" ]]; then
    ETL_LOADER=CreateColumnmetaCSV compose --profile etl run --rm --name bench-hpds-java-etl \
      bench-hpds-java-etl > "$dir/columnmeta.log" 2>&1
  fi
  return "$status"
}

case "${1:-}" in
  etl) run_etl "$2" ;;
  run) run_matrix "$2" "$3" "$4" ;;
  *) sed -n '2,20p' "$0" >&2; exit 2 ;;
esac
