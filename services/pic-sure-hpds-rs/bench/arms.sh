#!/usr/bin/env bash
# Benchmark arms: Java as-is, Java on the Rust layout, and hpds-rs, one container at a time on
# the same logical data, measured with hpds-bench load.
#
# Usage:
#   bench/arms.sh etl <csv> <out-dir> [--plain]
#       Runs hpds-etl load --mode new-search under /usr/bin/time -l. Records wall time, peak
#       RSS and store size in <out-dir>.etl.txt. Without --plain the store is encrypted with
#       <out-dir>/encryption_key, which must exist.
#   bench/arms.sh compare <arm-a> <data-a> <arm-b> <data-b> <corpus> <out>
#       Starts both arms on two ports and runs hpds-bench compare --divergences.
#   bench/arms.sh cold <arm> <data-dir> <corpus> <out-dir>
#       Drops the Docker VM page cache, starts a fresh container, records time to health UP,
#       then runs two passes at concurrency 1 (pass 1 cold, pass 2 warm). Leaves it running.
#   bench/arms.sh warm <arm> <corpus> <label> <out-dir>
#       On the running container: one unmeasured pass at concurrency 8, then a 60 s window at
#       each of CONCURRENCIES after a 10 s lead-in.
#   bench/arms.sh exports <arm> <corpus> <out-dir>
#       On the running container: async entries only, at each of EXPORT_CONCURRENCIES.
#   bench/arms.sh rest <arm> <data-dir> <corpus> <out-dir>
#       Container memory at rest: right after health UP on a fresh container, after one pass at
#       concurrency 1, and after 30 s idle. Each point records docker stats usage, the anon
#       bytes of the container cgroup's memory.stat and, for the Java arms, the heap in use after
#       a full GC (jcmd GC.run, then GC.heap_info). With KEEP_RUNNING=1 the container is left
#       up for warm and exports.
#   bench/arms.sh stop <arm> [log-file]
#       Stops the container gracefully (the layout arm logs its block cache statistics on
#       shutdown), saves its log to log-file when given, and removes it.
#
# A load run is recorded as failed in summary.tsv when hpds-bench exits non-zero, any request
# errors, or the container was OOM-killed or is no longer running afterwards. Memory under load
# is sampled every second as docker stats usage (which counts page cache) and as cgroup anon
# memory (which does not); both peak and average go in summary.tsv.
#
# Arms: java (hms-dbmi/pic-sure-hpds:bench on javabins), layout (hms-dbmi/pic-sure-hpds:layout
# with HPDS_STORE_FORMAT=layout on an encrypted hpds-rs store), rust (hms-dbmi/pic-sure-hpds-rs
# on an encrypted store), rust-plain (the same on a plain store, HPDS_ALLOW_PLAIN=true).
#
# Env: BENCH_BIN, ETL_BIN, HOST_PORT (18091), HOST_PORT_B (18092), DURATION (60s), LEAD_IN
#      (10s), CONCURRENCIES ("1 8 32"), EXPORT_CONCURRENCIES ("1 4"), EXCLUDE (result types
#      left out, comma separated, default none), JAVA_OPTS (-Xmx16g), CACHE_BYTES (4 GiB),
#      MEM_LIMIT (container memory limit, default none), TIMEOUT (120s), KEEP_RUNNING (0).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH_BIN="${BENCH_BIN:-$HERE/../target/release/hpds-bench}"
ETL_BIN="${ETL_BIN:-$HERE/../target/release/hpds-etl}"
HOST_PORT="${HOST_PORT:-18091}"
HOST_PORT_B="${HOST_PORT_B:-18092}"
DURATION="${DURATION:-60s}"
LEAD_IN="${LEAD_IN:-10s}"
CONCURRENCIES="${CONCURRENCIES:-1 8 32}"
EXPORT_CONCURRENCIES="${EXPORT_CONCURRENCIES:-1 4}"
EXCLUDE="${EXCLUDE:-}"
JAVA_OPTS="${JAVA_OPTS:--Xmx16g}"
CACHE_BYTES="${CACHE_BYTES:-4294967296}"
MEM_LIMIT="${MEM_LIMIT:-}"
TIMEOUT="${TIMEOUT:-120s}"
KEEP_RUNNING="${KEEP_RUNNING:-0}"

log() {
  printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" >&2
}

now() {
  python3 -c 'import time; print(time.time())'
}

container_of() {
  echo "bench-arm-$1"
}

image_of() {
  case "$1" in
    java) echo "${JAVA_HPDS_IMAGE:-hms-dbmi/pic-sure-hpds:bench}" ;;
    layout) echo "${LAYOUT_HPDS_IMAGE:-hms-dbmi/pic-sure-hpds:layout}" ;;
    rust | rust-plain) echo "${RUST_HPDS_IMAGE:-hms-dbmi/pic-sure-hpds-rs:LATEST}" ;;
    *) echo "unknown arm $1" >&2; return 2 ;;
  esac
}

arm_env() {
  local common=(-e SMALL_JOB_LIMIT=100 -e SMALL_TASK_THREADS=4 -e LARGE_TASK_THREADS=2 -e ID_BATCH_SIZE=1000)
  case "$1" in
    java)
      printf '%s\n' "${common[@]}" -e CACHE_SIZE=1500 -e PICSURE_ACTUATOR_EXPOSURE=health \
        -e hpds.requireAuthorizationFilter=false -e "JAVA_OPTS=$JAVA_OPTS" ;;
    layout)
      printf '%s\n' "${common[@]}" -e CACHE_SIZE=0 -e HPDS_STORE_FORMAT=layout -e "HPDS_CACHE_BYTES=$CACHE_BYTES" \
        -e PICSURE_ACTUATOR_EXPOSURE=health -e hpds.requireAuthorizationFilter=false -e "JAVA_OPTS=$JAVA_OPTS" ;;
    rust)
      printf '%s\n' "${common[@]}" -e "HPDS_CACHE_BYTES=$CACHE_BYTES" -e HPDS_REQUIRE_AUTHORIZATION_FILTER=false \
        -e 'RUST_LOG=info,hpds_rs::access=warn' ;;
    rust-plain)
      printf '%s\n' "${common[@]}" -e "HPDS_CACHE_BYTES=$CACHE_BYTES" -e HPDS_REQUIRE_AUTHORIZATION_FILTER=false \
        -e 'RUST_LOG=info,hpds_rs::access=warn' -e HPDS_ALLOW_PLAIN=true ;;
  esac
}

drop_vm_page_cache() {
  docker run --rm --name bench-drop-caches --privileged alpine:3.20 \
    sh -c 'sync; echo 3 > /proc/sys/vm/drop_caches'
}

wait_healthy() {
  local port="$1" container="$2"
  for _ in $(seq 1 2400); do
    if curl -fsS "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      return 0
    fi
    if [[ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" != "true" ]]; then
      docker logs --tail 40 "$container" >&2
      return 1
    fi
    sleep 0.25
  done
  docker logs --tail 40 "$container" >&2
  return 1
}

start_arm() {
  local arm="$1" data="$2" port="$3" name="${4:-$(container_of "$1")}"
  docker rm -f "$name" >/dev/null 2>&1 || true
  local args=()
  while IFS= read -r a; do args+=("$a"); done < <(arm_env "$arm")
  [[ -n "$MEM_LIMIT" ]] && args+=(--memory "$MEM_LIMIT" --memory-swap "$MEM_LIMIT")
  docker run -d --name "$name" -p "127.0.0.1:$port:8080" -v "$data:/opt/local/hpds" \
    "${args[@]}" "$(image_of "$arm")" >/dev/null
  wait_healthy "$port" "$name"
}

# Prints the anon memory of container $1 in MiB, from its cgroup v2 memory.stat.
anon_mib() {
  docker exec "$1" cat /sys/fs/cgroup/memory.stat 2>/dev/null | awk '$1 == "anon" {printf "%.0f", $2 / 1048576}'
}

# Appends one line per second to $2 until killed: docker stats usage in MiB, CPU %, and cgroup
# anon memory in MiB.
sample_stats() {
  local container="$1" out="$2" usage
  : > "$out"
  while true; do
    usage="$(docker stats --no-stream --format '{{.MemUsage}} {{.CPUPerc}}' "$container" 2>/dev/null \
      | awk '{
          v = $1; u = v; sub(/[0-9.]+/, "", u); sub(/[A-Za-z]+$/, "", v);
          if (u == "GiB") v *= 1024; else if (u == "KiB" || u == "kB") v /= 1024; else if (u == "B") v /= 1048576;
          c = $NF; sub(/%/, "", c); printf "%.0f %s", v, c
        }' || true)"
    [[ -n "$usage" ]] && echo "$usage $(anon_mib "$container")" >> "$out"
    sleep 1
  done
}

load_once() {
  local arm="$1" corpus="$2" name="$3" dir="$4"
  shift 4
  local container sampler status=0
  container="$(container_of "$arm")"
  if [[ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" != "true" ]]; then
    printf '%s\t%s\t\t\t\t\t\t\t\t\t\t\t\t\t\t\toom=%s\tfailed(container-down)\n' "$arm" "$name" \
      "$(docker inspect -f '{{.State.OOMKilled}}' "$container" 2>/dev/null || echo gone)" >> "$dir/summary.tsv"
    log "$arm $name skipped: container is not running"
    return 1
  fi
  local args=(--url "http://127.0.0.1:$HOST_PORT/PIC-SURE" --corpus "$corpus" --timeout "$TIMEOUT")
  [[ -n "$EXCLUDE" ]] && args+=(--exclude "$EXCLUDE")
  sample_stats "$container" "$dir/$name.stats" &
  sampler=$!
  "$BENCH_BIN" load "${args[@]}" "$@" > "$dir/$name.json" || status=$?
  kill "$sampler" 2>/dev/null || true
  wait "$sampler" 2>/dev/null || true
  local load1 peak avg peak_anon avg_anon cpu oom running errors result
  load1=$(sysctl -n vm.loadavg | awk '{print $2}')
  peak=$(awk '{print $1}' "$dir/$name.stats" | sort -n | tail -1)
  avg=$(awk '{s += $1; n++} END {if (n) printf "%.0f", s / n}' "$dir/$name.stats")
  peak_anon=$(awk 'NF >= 3 {print $3}' "$dir/$name.stats" | sort -n | tail -1)
  avg_anon=$(awk 'NF >= 3 {s += $3; n++} END {if (n) printf "%.0f", s / n}' "$dir/$name.stats")
  cpu=$(awk '$2 > 0 {s += $2; n++} END {if (n) printf "%.0f", s / n; else print 0}' "$dir/$name.stats")
  oom=$(docker inspect -f '{{.State.OOMKilled}}' "$container" 2>/dev/null || echo gone)
  running=$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || echo false)
  errors=$(jq -r '.overall.errors // empty' "$dir/$name.json" 2>/dev/null || true)
  if [[ "$status" != 0 || ! -s "$dir/$name.json" || -z "$errors" ]]; then
    result="failed(exit=$status)"
  elif [[ "$oom" != "false" ]]; then
    result="failed(oom)"
  elif [[ "$running" != "true" ]]; then
    result="failed(container-down)"
  elif [[ "$errors" != "0" ]]; then
    result="failed(errors=$errors)"
  else
    result="ok"
  fi
  if [[ -z "$errors" ]]; then
    printf '%s\t%s\t\t\t\t\t\t\t\t\t%s\t%s\t%s\t%s\t%s\t%s\toom=%s\t%s\n' "$arm" "$name" "${peak:-}" "${avg:-}" \
      "${peak_anon:-}" "${avg_anon:-}" "$cpu" "$load1" "$oom" "$result" >> "$dir/summary.tsv"
  else
    jq -r --arg a "$arm" --arg n "$name" --arg peak "${peak:-}" --arg avg "${avg:-}" --arg pa "${peak_anon:-}" \
      --arg aa "${avg_anon:-}" --arg cpu "$cpu" --arg load "$load1" --arg oom "$oom" --arg result "$result" \
      '[$a, $n, .overall.requests, .overall.errors, .overall.timeouts, (.overall.throughput_rps * 100 | round / 100),
        .overall.p50_ms, .overall.p90_ms, .overall.p99_ms, .overall.max_ms, $peak, $avg, $pa, $aa, $cpu, $load,
        ("oom=" + $oom), $result] | @tsv' \
      "$dir/$name.json" >> "$dir/summary.tsv"
  fi
  log "$(tail -1 "$dir/summary.tsv")"
  [[ "$result" == "ok" ]] || log "$arm $name $result"
}

init_dir() {
  mkdir -p "$1"
  [[ -s "$1/summary.tsv" ]] || printf 'arm\trun\trequests\terrors\ttimeouts\trps\tp50_ms\tp90_ms\tp99_ms\tmax_ms\tpeak_mem_mib\tavg_mem_mib\tpeak_anon_mib\tavg_anon_mib\tmean_cpu_pct\thost_load1\toom\tresult\n' > "$1/summary.tsv"
}

run_cold() {
  local arm="$1" data="$2" corpus="$3" dir="$4"
  init_dir "$dir"
  local container t0 t1
  container="$(container_of "$arm")"
  docker rm -f "$container" >/dev/null 2>&1 || true
  drop_vm_page_cache
  t0=$(now)
  start_arm "$arm" "$data" "$HOST_PORT"
  t1=$(now)
  awk -v a="$t0" -v b="$t1" 'BEGIN {printf "%.2f\n", b - a}' > "$dir/$arm.startup_s"
  docker stats --no-stream --format '{{.MemUsage}}' "$container" > "$dir/$arm.idle_mem"
  log "$arm health UP after $(cat "$dir/$arm.startup_s")s, idle $(cat "$dir/$arm.idle_mem")"
  load_once "$arm" "$corpus" "$arm-cold-passes" "$dir" --only sync --concurrency 1 --passes 2
}

run_warm() {
  local arm="$1" corpus="$2" label="$3" dir="$4"
  init_dir "$dir"
  local args=(--url "http://127.0.0.1:$HOST_PORT/PIC-SURE" --corpus "$corpus" --timeout "$TIMEOUT" --only sync)
  [[ -n "$EXCLUDE" ]] && args+=(--exclude "$EXCLUDE")
  "$BENCH_BIN" load "${args[@]}" --concurrency 8 --passes 1 > "$dir/$arm-$label-warmup.json"
  local c
  for c in $CONCURRENCIES; do
    load_once "$arm" "$corpus" "$arm-$label-c$c" "$dir" --only sync --concurrency "$c" \
      --duration "$DURATION" --warmup "$LEAD_IN" || return 0
  done
}

run_exports() {
  local arm="$1" corpus="$2" dir="$3"
  init_dir "$dir"
  local c
  for c in $EXPORT_CONCURRENCIES; do
    load_once "$arm" "$corpus" "$arm-exports-c$c" "$dir" --concurrency "$c" \
      --duration "$DURATION" --warmup "$LEAD_IN" || return 0
  done
}

mem_now() {
  docker stats --no-stream --format '{{.MemUsage}}' "$1" | awk '{print $1}'
}

# Prints the Java heap in use, in MiB, right after a full GC (jcmd GC.run, then the "used"
# figure of GC.heap_info), or nothing for a container without a JVM.
java_heap_after_gc_mib() {
  docker exec "$1" sh -c '
    pid=$(jcmd -l 2>/dev/null | awk "/service.jar/ {print \$1; exit}")
    [ -n "$pid" ] || exit 0
    jcmd "$pid" GC.run >/dev/null 2>&1
    jcmd "$pid" GC.heap_info 2>/dev/null' 2>/dev/null \
    | awk 'match($0, /used [0-9]+K/) {v = substr($0, RSTART + 5, RLENGTH - 6); printf "%.0f", v / 1024; exit}'
}

# Prints one rest measurement of container $1 as key=value pairs.
rest_point() {
  echo "usage=$(mem_now "$1") anon_mib=$(anon_mib "$1") heap_after_gc_mib=$(java_heap_after_gc_mib "$1") host_load1=$(sysctl -n vm.loadavg | awk '{print $2}')"
}

run_rest() {
  local arm="$1" data="$2" corpus="$3" dir="$4"
  mkdir -p "$dir"
  local container out="$dir/$arm.rest.txt"
  container="$(container_of "$arm")"
  docker rm -f "$container" >/dev/null 2>&1 || true
  drop_vm_page_cache
  start_arm "$arm" "$data" "$HOST_PORT"
  sleep 2
  {
    echo "arm=$arm java_opts=$JAVA_OPTS cache_bytes=$CACHE_BYTES mem_limit=$MEM_LIMIT"
    echo "rest_cold $(rest_point "$container")"
  } > "$out"
  local args=(--url "http://127.0.0.1:$HOST_PORT/PIC-SURE" --corpus "$corpus" --timeout "$TIMEOUT" --only sync)
  [[ -n "$EXCLUDE" ]] && args+=(--exclude "$EXCLUDE")
  "$BENCH_BIN" load "${args[@]}" --concurrency 1 --passes 1 > "$dir/$arm-rest-pass.json"
  echo "after_pass usage=$(mem_now "$container") anon_mib=$(anon_mib "$container")" >> "$out"
  sleep 30
  echo "rest_warm $(rest_point "$container")" >> "$out"
  cat "$out" >&2
  [[ "$KEEP_RUNNING" == "1" ]] || docker rm -f "$container" >/dev/null 2>&1 || true
}

stop_arm() {
  local container log_file="${2:-}"
  container="$(container_of "$1")"
  docker stop -t 30 "$container" >/dev/null 2>&1 || true
  [[ -z "$log_file" ]] || docker logs "$container" > "$log_file" 2>&1 || true
  docker rm -f "$container" >/dev/null 2>&1 || true
}

run_compare() {
  local arm_a="$1" data_a="$2" arm_b="$3" data_b="$4" corpus="$5" out="$6"
  start_arm "$arm_a" "$data_a" "$HOST_PORT" "bench-cmp-a"
  start_arm "$arm_b" "$data_b" "$HOST_PORT_B" "bench-cmp-b"
  local status=0
  "$BENCH_BIN" compare --a "http://127.0.0.1:$HOST_PORT/PIC-SURE" --b "http://127.0.0.1:$HOST_PORT_B/PIC-SURE" \
    --corpus "$corpus" --divergences "$HERE/divergences.json" --retries 1 > "$out" 2>&1 || status=$?
  docker rm -f bench-cmp-a bench-cmp-b >/dev/null 2>&1 || true
  tail -3 "$out" >&2
  return "$status"
}

run_etl() {
  local csv="$1" out="$2" plain="${3:-}"
  local args=(load --mode new-search --input "$csv" --out "$out")
  if [[ "$plain" == "--plain" ]]; then
    args+=(--plain)
  else
    [[ -s "$out/encryption_key" ]] || { echo "missing $out/encryption_key" >&2; return 1; }
  fi
  local t0 t1
  t0=$(now)
  /usr/bin/time -l "$ETL_BIN" "${args[@]}" > "$out.etl.log" 2>&1
  t1=$(now)
  {
    awk -v a="$t0" -v b="$t1" 'BEGIN {printf "wall_s=%.1f\n", b - a}'
    awk '/maximum resident set size/ {printf "peak_rss_mib=%.0f\n", $1 / 1048576}' "$out.etl.log"
    du -sk "$out"/pheno.index "$out"/pheno.blocks "$out"/columnMeta.csv
  } | tee "$out.etl.txt"
}

case "${1:-}" in
  etl) run_etl "$2" "$3" "${4:-}" ;;
  compare) run_compare "$2" "$3" "$4" "$5" "$6" "$7" ;;
  cold) run_cold "$2" "$3" "$4" "$5" ;;
  warm) run_warm "$2" "$3" "$4" "$5" ;;
  exports) run_exports "$2" "$3" "$4" ;;
  rest) run_rest "$2" "$3" "$4" "$5" ;;
  stop) stop_arm "$2" "${3:-}" ;;
  *) sed -n '2,/^set -euo/{/^set -euo/d;p;}' "$0" >&2; exit 2 ;;
esac
