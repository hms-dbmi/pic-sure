#!/usr/bin/env bash
# Loads a Rust phenotype store into $DOCKER_CONFIG_DIR/hpds-rs/.
#
# Usage: aio/load.sh [--dry-run] migrate|csv
#
#   migrate   converts the Java store in $DOCKER_CONFIG_DIR/hpds/ (mounted read-only,
#             never written) through bin/migrate-javabins with FORCE=1.
#   csv       runs hpds-etl load --mode sequential (the all-in-one's multi-file job, rollup
#             on) over $DOCKER_CONFIG_DIR/hpds_input/*.csv, mounted read-only, into a staging
#             directory inside hpds-rs/, then swaps the store files into place, pheno.index
#             last.
#
# Both copy the all-in-one's encryption_key into hpds-rs/ with mode 600, and keep an
# existing hpds-rs.env. If the hpds-rs container is running it is recreated afterwards,
# because its bind mount would otherwise keep serving the replaced files.
set -euo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"
}

# Loads the Java store in hpds/ into hpds-rs/ through bin/migrate-javabins.
load_migrate() {
  local java_etl name
  for name in columnMeta.javabin allObservationsStore.javabin encryption_key; do
    if [[ ! -r "$JAVA_STORE_DIR/$name" ]]; then
      if [[ "$DRY_RUN" == "1" ]]; then
        echo "note: $JAVA_STORE_DIR/$name is absent here; a real run would stop"
      else
        fail "$JAVA_STORE_DIR/$name is missing or unreadable. Has the Load HPDS Data From CSV job emptied hpds/?"
      fi
    fi
  done
  java_etl="$(find_java_etl_image)"
  if [[ -z "$java_etl" ]]; then
    if [[ "$DRY_RUN" == "1" ]]; then
      echo "note: no Java ETL image holds ExportForMigration; a real run would stop (run aio/build-images.sh --java-etl)"
      java_etl="hms-dbmi/pic-sure-hpds-etl:migrate"
    else
      fail "no local Java ETL image holds ExportForMigration. Run aio/build-images.sh --java-etl"
    fi
  fi
  act mkdir -p "$RS_STORE_DIR"
  local migrate_args=()
  [[ "$DRY_RUN" == "1" ]] && migrate_args+=(--dry-run)
  if [[ "$DRY_RUN" == "1" && ! -r "$JAVA_STORE_DIR/columnMeta.javabin" ]]; then
    echo "+ FORCE=1 JAVA_ETL_IMAGE=$java_etl RUST_ETL_IMAGE=$RUST_ETL_IMAGE $RS_ROOT/bin/migrate-javabins $JAVA_STORE_DIR $RS_STORE_DIR"
    echo "  (skipping its own dry run: the Java store is absent here)"
  else
    FORCE=1 JAVA_ETL_IMAGE="$java_etl" RUST_ETL_IMAGE="$RUST_ETL_IMAGE" \
      "$RS_ROOT/bin/migrate-javabins" ${migrate_args[@]+"${migrate_args[@]}"} "$JAVA_STORE_DIR" "$RS_STORE_DIR"
  fi
  install_key
}

# Copies the all-in-one's encryption key into hpds-rs/ with mode 600. An identical key already
# there is left in place (only its mode is set); a different key is refused.
install_key() {
  local source_key="$JAVA_STORE_DIR/encryption_key" target_key="$RS_STORE_DIR/encryption_key"
  if [[ "$DRY_RUN" == "1" && ! -r "$source_key" ]]; then
    echo "+ install -m 600 $source_key $target_key"
    return 0
  fi
  if [[ -e "$target_key" ]] && ! cmp -s "$source_key" "$target_key"; then
    fail "$target_key differs from $source_key; refusing to replace the key of an existing store"
  fi
  if [[ -e "$target_key" ]]; then
    log "encryption key already in place and identical"
    act chmod 600 "$target_key"
    return 0
  fi
  act install -m 600 "$source_key" "$target_key"
}

# Builds a store from hpds_input/*.csv with the Rust ETL and swaps it into hpds-rs/.
load_csv() {
  local inputs=() csv
  for csv in "$CSV_INPUT_DIR"/*.csv; do
    [[ -f "$csv" ]] && inputs+=("$csv")
  done
  if [[ ${#inputs[@]} -eq 0 ]]; then
    if [[ "$DRY_RUN" == "1" ]]; then
      echo "note: no CSV in $CSV_INPUT_DIR; a real run would stop"
      inputs+=("$CSV_INPUT_DIR/<file>.csv")
    else
      fail "no .csv files in $CSV_INPUT_DIR"
    fi
  fi
  if [[ ! -r "$JAVA_STORE_DIR/encryption_key" && ! -r "$RS_STORE_DIR/encryption_key" ]]; then
    if [[ "$DRY_RUN" == "1" ]]; then
      echo "note: no encryption key in hpds/ or hpds-rs/; a real run would stop"
    else
      fail "no encryption_key in $JAVA_STORE_DIR or $RS_STORE_DIR"
    fi
  fi

  local staging="$RS_STORE_DIR/.csv-next" container_inputs=() input
  for input in "${inputs[@]}"; do
    container_inputs+=("/input/$(basename "$input")")
  done

  act mkdir -p "$RS_STORE_DIR"
  act rm -rf "$staging"
  act mkdir -m 700 "$staging"
  install_key_into "$staging/encryption_key"
  local user
  user="$(id -u):$(id -g)"
  act docker run --rm --user "$user" \
    -v "$CSV_INPUT_DIR:/input:ro" \
    -v "$staging:/work" \
    "$RUST_ETL_IMAGE" load --mode sequential --input "${container_inputs[@]}" \
    --out /work/store --key-file /work/encryption_key
  swap_staged_store "$staging/store"
  act rm -rf "$staging"
  install_key
}

# Installs the key into $1 for the staged load, from hpds-rs/ when present, else hpds/.
install_key_into() {
  local target="$1" source_key="$JAVA_STORE_DIR/encryption_key"
  [[ -r "$RS_STORE_DIR/encryption_key" ]] && source_key="$RS_STORE_DIR/encryption_key"
  act install -m 600 "$source_key" "$target"
}

# Moves the finished store files from directory $1 into hpds-rs/, pheno.index last.
swap_staged_store() {
  local staged="$1" name
  if [[ "$DRY_RUN" != "1" ]]; then
    for name in pheno.blocks columnMeta.csv etl-report.json pheno.index; do
      [[ -f "$staged/$name" ]] || fail "hpds-etl did not write $name"
    done
  fi
  for name in pheno.blocks columnMeta.csv etl-report.json pheno.index; do
    act mv -f "$staged/$name" "$RS_STORE_DIR/$name"
  done
}

parse_args "$@"
[[ ${#ARGS[@]} -eq 1 ]] || { usage >&2; exit 2; }
mode="${ARGS[0]}"
require_config_dir
require_docker

case "$mode" in
  migrate) load_migrate ;;
  csv) load_csv ;;
  *) usage >&2; exit 2 ;;
esac

if container_running "$HPDS_RS_CONTAINER"; then
  log "recreating $HPDS_RS_CONTAINER so it opens the new store"
  up_args=()
  [[ "$DRY_RUN" == "1" ]] && up_args+=(--dry-run)
  "$AIO_DIR/up.sh" ${up_args[@]+"${up_args[@]}"}
else
  log "$HPDS_RS_CONTAINER is not running; start it with aio/up.sh"
fi
