#!/usr/bin/env bash
# Java vs Rust ETL parity for consent-partitioned loads.
#
# Each case loads one directory of headerless <partition>-allConcepts.csv files twice: with
# the harmonization branch's Java CSVLoader (hms-dbmi/pic-sure-hpds-etl:harm), which writes
# one javabin store per partition, and with `hpds-etl load --mode partitioned`. It then
# compares, partition by partition,
#   - the canonical dumps (Java DumpSourceCSV run on the partition, against hpds-etl dump of
#     the Rust partition), header lines skipped, numbers by parsed value,
#   - columnMeta.csv, every column but the block offset and length,
#   - the patient ID sets (the Java set read by migrating the Java store with
#     bin/migrate-javabins, which also gives a migration check per partition),
# and the two partition name sets. etl-parity-compare.py classifies each difference against
# etl-divergences.json with loader "partitioned" (and "migration-partitioned" for the
# migrated Java store against the Java dump).
#
# Usage: bench/etl-parity-partitioned.sh [case ...]   (default: harm-fixture edge edge-config)
#        bench/etl-parity-partitioned.sh --list
#
# Cases:
#   harm-fixture   the four partition files of the HPDS integration fixture
#   edge           crates/hpds-etl/tests/fixtures/partitioned/input without its config.json
#                  (Java gets no /opt/local/hpds_input, Rust gets --no-config)
#   edge-config    the same files with that config.json (mounted for Java, --config for Rust)
#   dir=<path>     any directory of partition files, as a case named after the directory
#   dev            $DEV_INPUT_DIR, the consent-partitioned dev set (heavy). When
#                  $DEV_JAVA_STORE holds a Java partitioned store built from it, that store is
#                  used read-only instead of loading again; likewise $DEV_RUST_STORE.
#
# Env:
#   JAVA_ETL_IMAGE   Java ETL image (hms-dbmi/pic-sure-hpds-etl:harm)
#   HPDS_ETL         hpds-etl binary (target-v2-par/release/hpds-etl, else target/release)
#   WORK_DIR         stores and dumps (${TMPDIR:-/tmp}/etl-parity-partitioned-work)
#   RESULTS_DIR      reports (bench/results/etl-parity-partitioned-<date>)
#   JAVA_MEMORY      memory cap of each Java container (6g); HEAPSIZE is derived from it
#
# Containers are named v2-par-etl-<case>-<step>, capped at JAVA_MEMORY, and removed on exit.
set -euo pipefail

HERE="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -P "$HERE/.." && pwd -P)"
REPO="$(cd -P "$ROOT/../.." && pwd -P)"
JAVA_ETL_IMAGE="${JAVA_ETL_IMAGE:-hms-dbmi/pic-sure-hpds-etl:harm}"
WORK_DIR="${WORK_DIR:-${TMPDIR:-/tmp}/etl-parity-partitioned-work}"
RESULTS_DIR="${RESULTS_DIR:-$HERE/results/etl-parity-partitioned-$(date +%F)}"
FIXTURE_DIR="${FIXTURE_DIR:-$REPO/services/pic-sure-hpds/service/src/test/resources/phenotypic}"
EDGE_DIR="${EDGE_DIR:-$ROOT/crates/hpds-etl/tests/fixtures/partitioned/input}"
DEV_INPUT_DIR="${DEV_INPUT_DIR:-}"
DEV_JAVA_STORE="${DEV_JAVA_STORE:-}"
DEV_RUST_STORE="${DEV_RUST_STORE:-}"
JAVA_MEMORY="${JAVA_MEMORY:-6g}"
HEAP_MIB=$(( ${JAVA_MEMORY%g} * 1024 * 3 / 4 ))
COMPARE=(python3 -I "$HERE/etl-parity-compare.py" --divergences "$HERE/etl-divergences.json")
USER_SPEC="$(id -u):$(id -g)"
CASES=(harm-fixture edge edge-config dev)

fail() {
  echo "etl-parity-partitioned: $*" >&2
  exit 2
}

log() {
  echo "[$(date +%H:%M:%S)] $*"
}

if [[ "${1:-}" == "--list" ]]; then
  printf '%s\n' "${CASES[@]}"
  exit 0
fi
cases=("$@")
[[ ${#cases[@]} -gt 0 ]] || cases=(harm-fixture edge edge-config)

if [[ -z "${HPDS_ETL:-}" ]]; then
  for candidate in "$ROOT/target-v2-par/release/hpds-etl" "$ROOT/target/release/hpds-etl"; do
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
SUMMARY="$RESULTS_DIR/summary.tsv"
[[ -f "$SUMMARY" ]] || printf 'case\tpartition\tstatus\treport\n' > "$SUMMARY"
failures=0

note() {
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$SUMMARY"
  [[ "$3" == "fail" ]] && failures=$((failures + 1))
  return 0
}

# Prints the partition names of a store directory: immediate subdirectories holding $2.
partitions_of() {
  local dir="$1" marker="$2" sub
  for sub in "$dir"/*/; do
    [[ -f "$sub/$marker" ]] && basename "$sub"
  done | sort
}

# Runs CSVLoader on the partition files of $1 into the new store $2. Arg 3 is a config.json
# to mount as /opt/local/hpds_input/config.json, or empty.
java_load() {
  local input="$1" store="$2" config="$3" name="$4" f
  mkdir -p "$store"
  openssl rand -hex 16 | tr -d '\n' > "$store/encryption_key"
  for f in "$input"/*-allConcepts.csv; do
    copy_file "$f" "$store/$(basename "$f")"
  done
  local mounts=(-v "$store:/opt/local/hpds")
  if [[ -n "$config" ]]; then
    mkdir -p "$w/java-in"
    cp "$config" "$w/java-in/config.json"
    mounts+=(-v "$w/java-in:/opt/local/hpds_input:ro")
  fi
  docker rm -f "v2-par-etl-$name-load" >/dev/null 2>&1 || true
  docker run --rm --name "v2-par-etl-$name-load" --memory "$JAVA_MEMORY" --user "$USER_SPEC" \
    -e LOADER_NAME=CSVLoader -e HEAPSIZE="$HEAP_MIB" "${mounts[@]}" "$JAVA_ETL_IMAGE" \
    > "$w/java-load.log" 2>&1 || return 1
  rm -f "$store"/*-allConcepts.csv
  ! grep -q "Exception in thread\|Caused by" "$w/java-load.log"
}

# Copies a file cheaply: a clone on APFS, a plain copy elsewhere.
copy_file() {
  cp -c "$1" "$2" 2>/dev/null || cp "$1" "$2"
}

# Dumps Java partition $2 of store $1 with DumpSourceCSV into $3. DumpSourceCSV reads and
# writes /opt/local/hpds, so the partition's javabin files and the key are copied into a
# scratch directory first and the store is never written.
java_dump() {
  local store="$1" partition="$2" out="$3" name="$4" scratch="$w/dump-scratch/$2" f
  rm -rf "$scratch"
  mkdir -p "$scratch"
  for f in columnMeta.javabin allObservationsStore.javabin; do
    copy_file "$store/$partition/$f" "$scratch/$f"
  done
  copy_file "$store/encryption_key" "$scratch/encryption_key"
  docker rm -f "v2-par-etl-$name-dump" >/dev/null 2>&1 || true
  docker run --rm --name "v2-par-etl-$name-dump" --memory "$JAVA_MEMORY" --user "$USER_SPEC" \
    -e LOADER_NAME=CSVDumper -e HEAPSIZE="$HEAP_MIB" \
    -v "$scratch:/opt/local/hpds" \
    "$JAVA_ETL_IMAGE" > "$w/java-dump-$partition.log" 2>&1
  mv "$scratch/allConcepts.csv" "$out"
  rm -rf "$scratch"
}

# One case. Args: case input_dir config(or empty) [prebuilt_java_store] [prebuilt_rust_store]
partition_case() {
  local case="$1" input="$2" config="$3" java_prebuilt="${4:-}" rust_prebuilt="${5:-}"
  w="$WORK_DIR/$case"
  rm -rf "$w"
  mkdir -p "$w/java-dump" "$w/rust-dump" "$w/migrated-dump"
  chmod 700 "$w"

  local java="$w/java" rust="$w/rust"
  if [[ -n "$java_prebuilt" ]]; then
    java="$java_prebuilt"
    log "$case: using the Java store $java"
  else
    log "$case: Java CSVLoader"
    java_load "$input" "$java" "$config" "$case" || fail "$case: Java load failed, see $w/java-load.log"
  fi
  if [[ -n "$rust_prebuilt" ]]; then
    rust="$rust_prebuilt"
    log "$case: using the Rust store $rust"
  else
    log "$case: hpds-etl load --mode partitioned"
    local config_args=(--no-config)
    [[ -n "$config" ]] && config_args=(--config "$config")
    "$HPDS_ETL" load --mode partitioned --input-dir "$input" --out "$rust" \
      --key-file "$java/encryption_key" "${config_args[@]}" > "$w/rust-load.log" 2>&1 \
      || fail "$case: Rust load failed, see $w/rust-load.log"
  fi

  local java_parts rust_parts
  java_parts="$(partitions_of "$java" columnMeta.javabin)"
  rust_parts="$(partitions_of "$rust" pheno.index)"
  if [[ "$java_parts" != "$rust_parts" ]]; then
    echo "partitions differ: Java [$(echo $java_parts)] Rust [$(echo $rust_parts)]" > "$RESULTS_DIR/$case.partitions.txt"
    note "$case" "*" fail "$RESULTS_DIR/$case.partitions.txt"
  fi

  log "$case: migrate the Java store for its patient sets"
  JAVA_ETL_IMAGE="$JAVA_ETL_IMAGE" HPDS_ETL="$HPDS_ETL" HEAPSIZE="$HEAP_MIB" \
    CONTAINER_PREFIX="v2-par-etl-$case-mig" EXPORT_DIR="$w/export" \
    "$ROOT/bin/migrate-javabins" "$java" "$w/java-migrated" > "$w/migrate.log" 2>&1 \
    || fail "$case: migrate-javabins failed, see $w/migrate.log"

  local p
  for p in $java_parts; do
    [[ -d "$rust/$p" ]] || continue
    log "$case/$p: dumps"
    java_dump "$java" "$p" "$w/java-dump/$p.csv" "$case"
    "$HPDS_ETL" dump --store "$rust/$p" --key-file "$rust/encryption_key" --out "$w/rust-dump/$p.csv" 2>>"$w/rust-dump.log"
    "$HPDS_ETL" dump --store "$rust/$p" --key-file "$rust/encryption_key" --patients --out "$w/rust-dump/$p.patients" 2>>"$w/rust-dump.log"
    "$HPDS_ETL" dump --store "$w/java-migrated/$p" --key-file "$w/java-migrated/encryption_key" --out "$w/migrated-dump/$p.csv" 2>>"$w/rust-dump.log"
    "$HPDS_ETL" dump --store "$w/java-migrated/$p" --key-file "$w/java-migrated/encryption_key" --patients --out "$w/migrated-dump/$p.patients" 2>>"$w/rust-dump.log"

    local report="$RESULTS_DIR/$case-$p.json"
    if "${COMPARE[@]}" --case "$case/$p" --loader partitioned --report "$report" \
      --java-dump "$w/java-dump/$p.csv" --rust-dump "$w/rust-dump/$p.csv" \
      --java-column-meta "$java/$p/columnMeta.csv" --rust-column-meta "$rust/$p/columnMeta.csv" \
      --java-patients "$w/migrated-dump/$p.patients" --rust-patients "$w/rust-dump/$p.patients" \
      > "$RESULTS_DIR/$case-$p.txt"; then
      note "$case" "$p" pass "$report"
    else
      note "$case" "$p" fail "$report"
    fi
    report="$RESULTS_DIR/$case-$p-migration.json"
    if "${COMPARE[@]}" --case "$case/$p-migration" --loader migration-partitioned --report "$report" \
      --java-dump "$w/java-dump/$p.csv" --rust-dump "$w/migrated-dump/$p.csv" \
      --java-column-meta "$java/$p/columnMeta.csv" --rust-column-meta "$w/java-migrated/$p/columnMeta.csv" \
      > "$RESULTS_DIR/$case-$p-migration.txt"; then
      note "$case" "$p-migration" pass "$report"
    else
      note "$case" "$p-migration" fail "$report"
    fi
  done
  rm -rf "$w/java-dump" "$w/rust-dump" "$w/migrated-dump" "$w/export"
}

for case in "${cases[@]}"; do
  case "$case" in
    harm-fixture) partition_case "$case" "$FIXTURE_DIR" "" ;;
    edge) partition_case "$case" "$EDGE_DIR" "" ;;
    edge-config) partition_case "$case" "$EDGE_DIR" "$EDGE_DIR/config.json" ;;
    dev)
      [[ -n "$DEV_INPUT_DIR" ]] || fail "set DEV_INPUT_DIR for $case"
      partition_case "$case" "$DEV_INPUT_DIR" "" "$DEV_JAVA_STORE" "$DEV_RUST_STORE"
      ;;
    dir=*) partition_case "$(basename "${case#dir=}")" "${case#dir=}" "" ;;
    *) fail "unknown case $case (see --list)" ;;
  esac
done

echo
column -t -s $'\t' "$SUMMARY"
if (( failures > 0 )); then
  echo "etl-parity-partitioned: $failures comparison(s) with unexpected differences"
  exit 1
fi
echo "etl-parity-partitioned: every difference is whitelisted"
