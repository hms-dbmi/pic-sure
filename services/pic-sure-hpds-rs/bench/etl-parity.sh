#!/usr/bin/env bash
# Java vs Rust ETL parity harness.
#
# For each case, loads the same CSV input with a Java loader (hms-dbmi/pic-sure-hpds-etl) and
# with the matching hpds-etl mode, dumps both stores, and compares
#   - the canonical dumps (Java DumpSourceCSV against hpds-etl dump), numbers by parsed value,
#   - columnMeta.csv, every column but the block offset and length,
#   - the patient ID sets (hpds-etl dump --patients against Java allIds, read by migrating the
#     Java store with bin/migrate-javabins).
# The migrated Java store is also compared with the Java dump, so every case doubles as a
# migration check. etl-parity-compare.py classifies each difference as WHITELISTED (a rule in
# etl-divergences.json, each citing the spec) or UNEXPECTED. Any unexpected difference fails
# the run.
#
# Usage: bench/etl-parity.sh [case ...]        (default: every case but the heavy ones)
#        bench/etl-parity.sh --list
#
# Cases:
#   g1k-newsearch, g1k-sequential    1000 Genomes fixture (its first line is read as the header)
#   edge-core-newsearch, edge-core-sequential, edge-lowram-sequential,
#   edge-config-newsearch, edge-config-sequential, edge-embedded-newline-sequential,
#   edge-negative-ids-newsearch, edge-negative-ids-sequential
#                                    hand-written edge cases under $EDGE_DIR/<name>/
#   edge-lowram-newsearch            the LowRAM edge file through CSVLoaderNewSearch, which
#                                    stops on its unparseable numbers
#   crash-one-field-sequential, crash-invalid-utf8-sequential
#                                    small generated files LowRAM stops on
#   dev-newsearch                    $DEV_DIR/allConcepts.csv (heavy)
#   dev-patient-sequential           $DEV_PATIENT_DIR/allConcepts.csv in one chunk (heavy)
#   aio                              the AIO hpds_input files and javabin store, copied from
#                                    $AIO_DIR first: Java LowRAM vs Rust sequential on the
#                                    three files, migration of the AIO store, and the AIO store
#                                    against the fresh Rust load. Reports hold counts only, and
#                                    every AIO-derived file but the migrated store is deleted.
#
# Env:
#   JAVA_ETL_IMAGE    Java ETL image (hms-dbmi/pic-sure-hpds-etl:bench)
#   HPDS_ETL          hpds-etl binary (default: target-parity/release/hpds-etl, else
#                     target/release/hpds-etl, under the Rust workspace)
#   WORK_DIR          where stores, copies and dumps go (${TMPDIR:-/tmp}/etl-parity-work)
#   RESULTS_DIR       where reports go (bench/results/etl-parity-<date>)
#   FIXTURE_CSV       1000 Genomes fixture
#   EDGE_DIR, DEV_DIR, DEV_PATIENT_DIR   input directories for the edge and dev cases
#   AIO_DIR           the AIO's local_all_in_one directory, read only; nothing there is
#                     mounted into a container or written
#   AIO_MIGRATED_OUT  where the migrated AIO store is kept ($WORK_DIR/aio-migrated)
#   DOCKER_FREE_MIB   memory a heavy Java step needs free in Docker before it starts; the
#                     script waits up to WAIT_MINUTES (30) and then skips the case
#
# Containers are named parity-<case>-<step> and removed when they exit.
set -euo pipefail

HERE="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -P "$HERE/.." && pwd -P)"
REPO="$(cd -P "$ROOT/../.." && pwd -P)"
JAVA_ETL_IMAGE="${JAVA_ETL_IMAGE:-hms-dbmi/pic-sure-hpds-etl:bench}"
WORK_DIR="${WORK_DIR:-${TMPDIR:-/tmp}/etl-parity-work}"
RESULTS_DIR="${RESULTS_DIR:-$HERE/results/etl-parity-$(date +%F)}"
FIXTURE_CSV="${FIXTURE_CSV:-$REPO/services/pic-sure-hpds/service/src/test/resources/phenotypic/allConcepts.csv}"
EDGE_DIR="${EDGE_DIR:-}"
DEV_DIR="${DEV_DIR:-}"
DEV_PATIENT_DIR="${DEV_PATIENT_DIR:-}"
AIO_DIR="${AIO_DIR:-}"
AIO_MIGRATED_OUT="${AIO_MIGRATED_OUT:-$WORK_DIR/aio-migrated}"
WAIT_MINUTES="${WAIT_MINUTES:-30}"
DIVERGENCES="$HERE/etl-divergences.json"
COMPARE=(python3 -I "$HERE/etl-parity-compare.py" --divergences "$DIVERGENCES")
USER_SPEC="$(id -u):$(id -g)"

LIGHT_CASES=(
  g1k-newsearch g1k-sequential
  edge-core-newsearch edge-core-sequential edge-lowram-sequential
  edge-config-newsearch edge-config-sequential edge-embedded-newline-sequential
  edge-negative-ids-newsearch edge-negative-ids-sequential
  edge-lowram-newsearch crash-one-field-sequential crash-invalid-utf8-sequential
)
HEAVY_CASES=(dev-newsearch dev-patient-sequential aio)

fail() {
  echo "etl-parity: $*" >&2
  exit 2
}

log() {
  echo "[$(date +%H:%M:%S)] $*"
}

if [[ "${1:-}" == "--list" ]]; then
  printf '%s\n' "${LIGHT_CASES[@]}" "${HEAVY_CASES[@]}"
  exit 0
fi
cases=("$@")
[[ ${#cases[@]} -gt 0 ]] || cases=("${LIGHT_CASES[@]}")

if [[ -z "${HPDS_ETL:-}" ]]; then
  for candidate in "$ROOT/target-parity/release/hpds-etl" "$ROOT/target/release/hpds-etl"; do
    if [[ -x "$candidate" ]]; then
      HPDS_ETL="$candidate"
      break
    fi
  done
fi
[[ -n "${HPDS_ETL:-}" && -x "$HPDS_ETL" ]] || fail "no hpds-etl binary; build one or set HPDS_ETL"
docker image inspect "$JAVA_ETL_IMAGE" >/dev/null 2>&1 || fail "image $JAVA_ETL_IMAGE not found"

mkdir -p "$WORK_DIR" "$RESULTS_DIR"
WORK_DIR="$(cd -P "$WORK_DIR" && pwd -P)"
if [[ -n "$AIO_DIR" ]]; then
  AIO_DIR="$(cd -P "$AIO_DIR" && pwd -P)"
  [[ "$WORK_DIR" != "$AIO_DIR" && "$WORK_DIR" != "$AIO_DIR"/* ]] || fail "WORK_DIR is inside AIO_DIR"
fi
SUMMARY="$RESULTS_DIR/summary.tsv"
[[ -f "$SUMMARY" ]] || printf 'case\tstatus\treport\n' > "$SUMMARY"
failures=0

# Runs one Java ETL step. Args: case step heap_mib loader_args store_dir [input_dir]. The
# container's output goes to $w/<step>.log; the exit status is the container's.
java_step() {
  local case="$1" step="$2" heap="$3" loader="$4" args="$5" store="$6" input="${7:-}"
  local mounts=(-v "$store:/opt/local/hpds")
  [[ -n "$input" ]] && mounts+=(-v "$input:/opt/local/hpds_input:ro")
  docker rm -f "parity-$case-$step" >/dev/null 2>&1 || true
  docker run --rm --name "parity-$case-$step" --user "$USER_SPEC" \
    -e LOADER_NAME="$loader" -e LOADER_ARGS="$args" -e HEAPSIZE="$heap" \
    "${mounts[@]}" "$JAVA_ETL_IMAGE" > "$w/$step.log" 2>&1
}

# Copies a file cheaply: a clone on APFS, a plain copy elsewhere.
copy_file() {
  cp -c "$1" "$2" 2>/dev/null || cp "$1" "$2"
}

# Waits until Docker has $1 MiB free next to the running containers. Returns 1 after
# WAIT_MINUTES without that much free.
wait_for_docker_memory() {
  local need="$1" total used waited=0
  total=$(( $(docker info --format '{{.MemTotal}}') / 1048576 ))
  while true; do
    used=$(docker stats --no-stream --format '{{.MemUsage}}' | awk '{
      v=$1; u=v; gsub(/[0-9.]/,"",u); gsub(/[A-Za-z]/,"",v);
      if (u=="GiB") v*=1024; else if (u=="KiB") v/=1024; else if (u=="B") v/=1048576;
      s+=v } END { printf "%d", s }')
    if (( total - used >= need )); then
      return 0
    fi
    if (( waited >= WAIT_MINUTES )); then
      echo "Docker has $((total - used)) MiB free of $total, needs $need" > "$w/skipped.txt"
      return 1
    fi
    log "$case: Docker has $((total - used)) MiB free, needs $need; waiting"
    sleep 60
    waited=$((waited + 1))
  done
}

# Loads the inputs with a Java loader into $w/java and writes $w/java-dump.csv,
# $w/java/columnMeta.csv, and, through bin/migrate-javabins, $w/java-patients.txt and
# $w/java-migrated-dump.csv. Args: case loader heap max_chunk_gib config inputs...
# Sets java_ok and migrate_ok (1 or 0) and java_order (the files in the order the loader
# read them).
java_side() {
  local case="$1" loader="$2" heap="$3" chunk="$4" config="$5"
  shift 5
  local inputs=("$@")
  rm -rf "$w/java" "$w/java-in" "$w/java-migrated" "$w/export"
  mkdir -p "$w/java" "$w/java-in"
  copy_file "$w/encryption_key" "$w/java/encryption_key"
  [[ -n "$config" ]] && copy_file "$config" "$w/java-in/config.json"
  java_ok=1
  migrate_ok=0
  java_order=()
  if [[ "$loader" == "newsearch" ]]; then
    copy_file "${inputs[0]}" "$w/java/allConcepts.csv"
    log "$case: Java CSVLoaderNewSearch"
    java_step "$case" java-load "$heap" CSVLoaderNewSearch "" "$w/java" "$w/java-in" || java_ok=0
    rm -f "$w/java/allConcepts.csv"
    java_order=("${inputs[0]}")
  else
    local f
    for f in "${inputs[@]}"; do copy_file "$f" "$w/java-in/$(basename "$f")"; done
    log "$case: Java LowRAMMultiCSVLoader MAX_CHUNK_SIZE=$chunk"
    java_step "$case" java-load "$heap" LowRAMMultiCSVLoader "MAX_CHUNK_SIZE=$chunk" "$w/java" "$w/java-in" || java_ok=0
    while IFS= read -r f; do
      java_order+=("$w/java-in/$f")
    done < <(sed -n 's|.*Confirmed file /opt/local/hpds_input/\(.*\) is a \.csv.*|\1|p' "$w/java-load.log")
  fi
  if [[ "$java_ok" == "1" && ! -s "$w/java/columnMeta.javabin" ]]; then
    java_ok=0
  fi
  if grep -q "Exception in thread\|Caused by" "$w/java-load.log"; then
    java_ok=0
  fi
  [[ "$java_ok" == "1" ]] || { log "$case: Java load failed"; return 0; }

  log "$case: Java DumpSourceCSV"
  java_step "$case" java-dump 4096 CSVDumper "" "$w/java" || fail "$case: DumpSourceCSV failed, see $w/java-dump.log"
  mv "$w/java/allConcepts.csv" "$w/java-dump.csv"

  log "$case: migrate the Java store for its patient set"
  migrate_ok=1
  EXPORT_DIR="$w/export" HPDS_ETL="$HPDS_ETL" HEAPSIZE=4096 \
    "$ROOT/bin/migrate-javabins" "$w/java" "$w/java-migrated" > "$w/migrate.log" 2>&1 || migrate_ok=0
  [[ "$migrate_ok" == "1" ]] || { log "$case: migrate-javabins failed, see $w/migrate.log"; return 0; }
  "$HPDS_ETL" dump --store "$w/java-migrated" --patients --out "$w/java-patients.txt" 2>>"$w/rust-dump.log"
  "$HPDS_ETL" dump --store "$w/java-migrated" --out "$w/java-migrated-dump.csv" 2>>"$w/rust-dump.log"
}

# Loads the inputs with hpds-etl into $w/rust and dumps it. Args: case mode chunk config
# inputs... Sets rust_ok.
rust_side() {
  local case="$1" mode="$2" chunk="$3" config="$4"
  shift 4
  local args=(load --mode "$mode" --out "$w/rust" --input "$@")
  [[ -n "$config" ]] && args+=(--config "$config")
  [[ "$mode" == "sequential" ]] && args+=(--max-chunk-gib "$chunk")
  rm -rf "$w/rust"
  mkdir -p "$w/rust"
  copy_file "$w/encryption_key" "$w/rust/encryption_key"
  log "$case: hpds-etl load --mode $mode"
  rust_ok=1
  "$HPDS_ETL" "${args[@]}" > "$w/rust-load.log" 2>&1 || rust_ok=0
  if [[ "$rust_ok" == "1" ]]; then
    "$HPDS_ETL" dump --store "$w/rust" --out "$w/rust-dump.csv" 2>>"$w/rust-dump.log"
    "$HPDS_ETL" dump --store "$w/rust" --patients --out "$w/rust-patients.txt" 2>>"$w/rust-dump.log"
  fi
}

# Records a compare result. Args: case status report.
note() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$SUMMARY"
  [[ "$2" == "fail" ]] && failures=$((failures + 1))
  return 0
}

# Runs the compare step. Args: case loader [extra compare args...]; the report goes to
# $RESULTS_DIR/<case>.json.
compare() {
  local case="$1" loader="$2"
  shift 2
  local report="$RESULTS_DIR/$case.json"
  if "${COMPARE[@]}" --case "$case" --loader "$loader" --report "$report" "$@" | tee "$RESULTS_DIR/$case.txt"; then
    note "$case" pass "$report"
  else
    note "$case" fail "$report"
  fi
}

# One CSV case: Java loader against hpds-etl. Args: case loader heap chunk config
# expect_rust_failure inputs...
csv_case() {
  local case="$1" loader="$2" heap="$3" chunk="$4" config="$5" expect_reject="$6"
  shift 6
  local inputs=("$@") f
  for f in "${inputs[@]}"; do [[ -f "$f" ]] || fail "$case: input $f is missing"; done
  w="$WORK_DIR/$case"
  rm -rf "$w"
  mkdir -p "$w"
  openssl rand -hex 16 | tr -d '\n' > "$w/encryption_key"
  java_side "$case" "$loader" "$heap" "$chunk" "$config" "${inputs[@]}"
  local mode=new-search rollup=()
  if [[ "$loader" == "lowram" ]]; then
    mode=sequential
    rollup=(--rollup)
    [[ ${#java_order[@]} -gt 0 ]] && inputs=("${java_order[@]}")
  fi
  if [[ "$java_ok" == "1" && "$migrate_ok" != "1" && "$expect_reject" != "1" ]]; then
    fail "$case: migrate-javabins failed, see $w/migrate.log"
  fi
  rust_side "$case" "$mode" "$chunk" "$config" "${inputs[@]}"

  local args=(--max-chunk-gib "$chunk" "${rollup[@]}")
  for f in "${inputs[@]}"; do args+=(--input "$f"); done
  [[ -n "$config" ]] && args+=(--config "$config")
  [[ "$expect_reject" == "1" ]] && args+=(--expect-rust-failure)
  [[ "$rust_ok" == "1" ]] || args+=(--rust-log "$w/rust-load.log")
  if [[ "$java_ok" != "1" ]]; then
    args+=(--java-log "$w/java-load.log")
  elif [[ "$expect_reject" == "1" ]]; then
    {
      echo "Java outcome on input the Rust ETL rejects:"
      echo "  dump rows: $(($(wc -l < "$w/java-dump.csv") - 1))"
      echo "  patients in the dump: $(sed 1d "$w/java-dump.csv" | cut -d, -f1 | sort -un | tr '\n' ' ')"
      echo "  bin/migrate-javabins: $( [[ "$migrate_ok" == "1" ]] && echo accepted || grep -m1 '^hpds-etl:' "$w/migrate.log" | sed 's|.*: line|refused, line|' || echo failed)"
      echo "  dump:"
      sed 1d "$w/java-dump.csv" | sed 's/^/    /'
    } > "$RESULTS_DIR/$case.java-outcome.txt"
  fi
  if [[ "$java_ok" == "1" && "$rust_ok" == "1" ]]; then
    args+=(--java-dump "$w/java-dump.csv" --rust-dump "$w/rust-dump.csv"
      --java-column-meta "$w/java/columnMeta.csv" --rust-column-meta "$w/rust/columnMeta.csv"
      --java-patients "$w/java-patients.txt" --rust-patients "$w/rust-patients.txt")
  fi
  compare "$case" "$loader" "${args[@]}"
  if [[ "$java_ok" == "1" && "$migrate_ok" == "1" ]]; then
    compare "$case-migration" "migration-$loader" \
      --java-dump "$w/java-dump.csv" --rust-dump "$w/java-migrated-dump.csv" \
      --java-column-meta "$w/java/columnMeta.csv" --rust-column-meta "$w/java-migrated/columnMeta.csv"
  fi
}

# The AIO case. Copies the AIO's hpds_input CSVs and javabin store into $WORK_DIR/aio-copy
# (the originals are only ever read by cp), then runs Java LowRAM against hpds-etl sequential
# on the three files, migrates the copied store into $AIO_MIGRATED_OUT, compares the migrated
# store with Java DumpSourceCSV on the copy, and compares the AIO store with the fresh Rust
# load. Every report is redacted to counts, and every AIO-derived file but the migrated store
# is deleted at the end.
aio_case() {
  [[ -n "$AIO_DIR" ]] || fail "aio: set AIO_DIR to the AIO's local_all_in_one directory"
  local copy="$WORK_DIR/aio-copy" f
  rm -rf "$copy"
  mkdir -p "$copy/hpds_input" "$copy/hpds"
  chmod 700 "$copy"
  for f in nhanes.csv synthea.csv 1000_genomes.csv; do
    cp "$AIO_DIR/hpds_input/$f" "$copy/hpds_input/$f"
  done
  for f in columnMeta.javabin allObservationsStore.javabin encryption_key columnMeta.csv; do
    cp "$AIO_DIR/hpds/$f" "$copy/hpds/$f"
  done
  trap 'rm -rf "$WORK_DIR/aio-copy" "$WORK_DIR/aio-sequential" "$WORK_DIR/aio-store"' EXIT

  w="$WORK_DIR/aio-sequential"
  if wait_for_docker_memory 14000; then
    rm -rf "$w"
    mkdir -p "$w"
    chmod 700 "$w"
    copy_file "$copy/hpds/encryption_key" "$w/encryption_key"
    java_side aio-sequential lowram 12000 5 "" "$copy/hpds_input/nhanes.csv" "$copy/hpds_input/synthea.csv" "$copy/hpds_input/1000_genomes.csv"
    [[ "$java_ok" == "1" ]] || fail "aio-sequential: Java load failed, see $w/java-load.log"
    local inputs=("${java_order[@]}") args=(--redact --rollup --max-chunk-gib 5)
    rust_side aio-sequential sequential 5 "" "${inputs[@]}"
    [[ "$rust_ok" == "1" ]] || fail "aio-sequential: hpds-etl load failed, see $w/rust-load.log"
    for f in "${inputs[@]}"; do args+=(--input "$f"); done
    compare aio-sequential lowram "${args[@]}" \
      --java-dump "$w/java-dump.csv" --rust-dump "$w/rust-dump.csv" \
      --java-column-meta "$w/java/columnMeta.csv" --rust-column-meta "$w/rust/columnMeta.csv" \
      --java-patients "$w/java-patients.txt" --rust-patients "$w/rust-patients.txt"
    compare aio-sequential-migration migration-lowram --redact \
      --java-dump "$w/java-dump.csv" --rust-dump "$w/java-migrated-dump.csv" \
      --java-column-meta "$w/java/columnMeta.csv" --rust-column-meta "$w/java-migrated/columnMeta.csv"
  else
    note aio-sequential skipped "$w/skipped.txt"
  fi

  w="$WORK_DIR/aio-store"
  rm -rf "$w"
  mkdir -p "$w/java"
  chmod 700 "$w"
  for f in columnMeta.javabin allObservationsStore.javabin encryption_key; do
    copy_file "$copy/hpds/$f" "$w/java/$f"
  done
  log "aio-migration: Java DumpSourceCSV on the copied AIO store"
  java_step aio-migration java-dump 4096 CSVDumper "" "$w/java" || fail "aio-migration: DumpSourceCSV failed, see $w/java-dump.log"
  mv "$w/java/allConcepts.csv" "$w/java-dump.csv"
  log "aio-migration: bin/migrate-javabins into $AIO_MIGRATED_OUT"
  rm -rf "$AIO_MIGRATED_OUT"
  EXPORT_DIR="$w/export" HPDS_ETL="$HPDS_ETL" HEAPSIZE=4096 \
    "$ROOT/bin/migrate-javabins" "$copy/hpds" "$AIO_MIGRATED_OUT" > "$w/migrate.log" 2>&1 \
    || fail "aio-migration: migrate-javabins failed, see $w/migrate.log"
  "$HPDS_ETL" dump --store "$AIO_MIGRATED_OUT" --out "$w/migrated-dump.csv" 2>>"$w/rust-dump.log"
  "$HPDS_ETL" dump --store "$AIO_MIGRATED_OUT" --patients --out "$w/migrated-patients.txt" 2>>"$w/rust-dump.log"
  compare aio-migration migration-lowram --redact \
    --java-dump "$w/java-dump.csv" --rust-dump "$w/migrated-dump.csv" \
    --java-column-meta "$copy/hpds/columnMeta.csv" --rust-column-meta "$AIO_MIGRATED_OUT/columnMeta.csv"

  local fresh="$WORK_DIR/aio-sequential"
  if [[ -s "$fresh/rust-dump.csv" ]]; then
    local args=(--redact --rollup --max-chunk-gib 5)
    for f in "${java_order[@]}"; do args+=(--input "$f"); done
    compare aio-store-vs-rust-fresh lowram "${args[@]}" \
      --java-dump "$w/java-dump.csv" --rust-dump "$fresh/rust-dump.csv" \
      --java-column-meta "$copy/hpds/columnMeta.csv" --rust-column-meta "$fresh/rust/columnMeta.csv" \
      --java-patients "$w/migrated-patients.txt" --rust-patients "$fresh/rust-patients.txt"
    compare aio-store-vs-java-fresh migration-lowram --redact \
      --java-dump "$w/java-dump.csv" --rust-dump "$fresh/java-dump.csv" \
      --java-column-meta "$copy/hpds/columnMeta.csv" --rust-column-meta "$fresh/java/columnMeta.csv" \
      --java-patients "$w/migrated-patients.txt" --rust-patients "$fresh/java-patients.txt"
  fi
  rm -rf "$WORK_DIR/aio-copy" "$WORK_DIR/aio-sequential" "$WORK_DIR/aio-store"
  trap - EXIT
}

# Writes a small generated input for the crash cases and prints its path. Arg: case.
crash_input() {
  local dir="$WORK_DIR/inputs/$1"
  mkdir -p "$dir"
  case "$1" in
    crash-one-field-sequential)
      printf 'PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n1,\\crash\\a\\,1,,\n2\n3,\\crash\\a\\,2,,\n' ;;
    crash-invalid-utf8-sequential)
      printf 'PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n1,\\crash\\a\\,,x\377\376y,\n3,\\crash\\a\\,,z,\n' ;;
  esac > "$dir/allConcepts.csv"
  echo "$dir/allConcepts.csv"
}

edge() {
  [[ -n "$EDGE_DIR" ]] || fail "set EDGE_DIR for the edge cases"
  echo "$EDGE_DIR/$1"
}

for case in "${cases[@]}"; do
  case "$case" in
    g1k-newsearch) csv_case "$case" newsearch 2048 5 "" 0 "$FIXTURE_CSV" ;;
    g1k-sequential) csv_case "$case" lowram 2048 5 "" 0 "$FIXTURE_CSV" ;;
    edge-core-newsearch) csv_case "$case" newsearch 1024 5 "" 0 "$(edge core)/allConcepts.csv" ;;
    edge-core-sequential) csv_case "$case" lowram 1024 5 "" 0 "$(edge core)/allConcepts.csv" ;;
    edge-lowram-sequential) csv_case "$case" lowram 1024 5 "" 0 "$(edge lowram)/allConcepts.csv" ;;
    edge-config-newsearch) csv_case "$case" newsearch 1024 5 "$(edge config)/config.json" 0 "$(edge config)/allConcepts.csv" ;;
    edge-config-sequential) csv_case "$case" lowram 1024 5 "$(edge config)/config.json" 0 "$(edge config)/allConcepts.csv" ;;
    edge-embedded-newline-sequential) csv_case "$case" lowram 1024 5 "" 0 "$(edge embedded-newline)/allConcepts.csv" ;;
    edge-negative-ids-newsearch) csv_case "$case" newsearch 1024 5 "" 1 "$(edge negative-ids)/allConcepts.csv" ;;
    edge-negative-ids-sequential) csv_case "$case" lowram 1024 5 "" 1 "$(edge negative-ids)/allConcepts.csv" ;;
    edge-lowram-newsearch) csv_case "$case" newsearch 1024 5 "" 0 "$(edge lowram)/allConcepts.csv" ;;
    crash-one-field-sequential | crash-invalid-utf8-sequential)
      csv_case "$case" lowram 512 5 "" 0 "$(crash_input "$case")" ;;
    dev-newsearch)
      [[ -n "$DEV_DIR" ]] || fail "set DEV_DIR for $case"
      w="$WORK_DIR/$case"
      mkdir -p "$w"
      if wait_for_docker_memory "${DOCKER_FREE_MIB:-10000}"; then
        csv_case "$case" newsearch 8192 5 "" 0 "$DEV_DIR/allConcepts.csv"
      else
        note "$case" skipped "$w/skipped.txt"
      fi
      ;;
    dev-patient-sequential)
      [[ -n "$DEV_PATIENT_DIR" ]] || fail "set DEV_PATIENT_DIR for $case"
      w="$WORK_DIR/$case"
      mkdir -p "$w"
      if wait_for_docker_memory "${DOCKER_FREE_MIB:-22000}"; then
        csv_case "$case" lowram 21000 3 "" 0 "$DEV_PATIENT_DIR/allConcepts.csv"
      else
        note "$case" skipped "$w/skipped.txt"
      fi
      ;;
    aio) aio_case ;;
    *) fail "unknown case $case (see --list)" ;;
  esac
done

echo
column -t -s $'\t' "$SUMMARY"
if (( failures > 0 )); then
  echo "etl-parity: $failures comparison(s) with unexpected differences"
  exit 1
fi
echo "etl-parity: every difference is whitelisted"
