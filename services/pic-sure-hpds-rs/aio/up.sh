#!/usr/bin/env bash
# Creates or replaces the hpds-rs container beside the Java HPDS.
#
# Usage: aio/up.sh [--dry-run]
#
# The container joins the picsure network and the internal hpds network, restarts always,
# and is never named by start-picsure.sh, so Start PIC-SURE leaves it alone. Mounts:
#
#   $DOCKER_CONFIG_DIR/hpds-rs          -> /opt/local/hpds           (read-only)
#   $DOCKER_CONFIG_DIR/hpds-rs-results  -> /var/lib/hpds-rs/results
#   $DOCKER_CONFIG_DIR/log/hpds-rs-logs -> /var/log/hpds-rs
#   $HPDS_RS_GENOMIC_DIR                -> /opt/local/genomic        (read-only, when set)
#
# HPDS_RS_STORE_DIR replaces $DOCKER_CONFIG_DIR/hpds-rs as the phenotype store, and its own
# hpds-rs.env is the one read, so a second store can be served without touching the first.
# HPDS_RS_GENOMIC_DIR names a genomic store (geno.index) built by hpds-etl genomic-migrate or
# genomic-load and sealed with the phenotype store's key; HPDS_GENOMIC_DATA_DIRECTORY is set
# to the mount. It must lie outside $DOCKER_CONFIG_DIR/hpds and the phenotype store, and
# hpds-rs.env must set hpds.genomicProcessor.impl (or HPDS_GENOMICPROCESSOR_IMPL) to the
# store's layout. The Java hpds.env spelling HPDS_GENOMIC_PROCESSOR_IMPL is not read by
# hpds-rs and is refused there, since it would serve no genomic data.
#
# The container is created, attached to both networks, then started. An existing hpds-rs is
# replaced safely: the new one is created under a temporary name, started, and must report
# healthy before the names are swapped. If the new one does not come up it is removed and the
# old one keeps running, so a query service pointed at hpds-rs stays served. Both containers
# share the results directory while they overlap.
#
# After the swap the old container keeps running as hpds-rs-old for a grace period
# (HPDS_RS_SWAP_GRACE_SECONDS, default 60) before it is stopped and removed. The rename takes
# it out of Docker DNS but leaves its address and its network attachments alone, so a Java
# query service that cached the old address (the JVM keeps a DNS answer for about 30 seconds)
# keeps getting answers instead of connect timeouts until its cache expires. Interrupting the
# grace period removes the old container at once.
#
# Settings come from $DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env, written on the first run from
# the Java hpds.env: ID_BATCH_SIZE, SMALL_TASK_THREADS, LARGE_TASK_THREADS and
# hpds.requireAuthorizationFilter (true when absent). Later runs never rewrite it. Fails when
# the networks are missing (start the AIO first) or the store has not been loaded (run
# aio/load.sh). Waits for the healthcheck.
set -euo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"
}

# Prints the Java hpds.env value of key $1 for hpds-rs.env, or the default $2 when absent.
# $3 is number or boolean; any other value fails instead of being copied.
java_setting() {
  local value
  value="$(java_env_value "$1")"
  [[ -n "$value" ]] || value="$2"
  case "$3" in
    number) [[ "$value" =~ ^[0-9]+$ ]] || fail "$1=$value in $JAVA_STORE_DIR/hpds.env is not a whole number" ;;
    boolean) [[ "$value" == "true" || "$value" == "false" ]] || fail "$1=$value in $JAVA_STORE_DIR/hpds.env is not true or false" ;;
  esac
  echo "$value"
}

SWAP_GRACE_SECONDS="${HPDS_RS_SWAP_GRACE_SECONDS:-60}"

parse_args "$@"
[[ ${#ARGS[@]} -eq 0 ]] || { usage >&2; exit 2; }
[[ "$SWAP_GRACE_SECONDS" =~ ^[0-9]+$ ]] || fail "HPDS_RS_SWAP_GRACE_SECONDS=$SWAP_GRACE_SECONDS is not a whole number of seconds"
require_config_dir
require_docker
require_networks

docker image inspect "$HPDS_RS_IMAGE" >/dev/null 2>&1 \
  || fail "image $HPDS_RS_IMAGE not found. Run aio/build-images.sh"

if [[ ! -f "$RS_STORE_DIR/pheno.index" ]]; then
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "note: $RS_STORE_DIR has no store; a real run would stop (run aio/load.sh)"
  else
    fail "$RS_STORE_DIR has no store (pheno.index is missing). Run aio/load.sh migrate or csv first."
  fi
fi

# Exits unless directory $1 is neither directory $2 nor inside it. $3 names $2 in the message.
refuse_inside() {
  case "$1/" in
    "$2"/*) fail "HPDS_RS_GENOMIC_DIR=$1 lies in $3 ($2); build the genomic store in its own directory" ;;
  esac
}

genomic_args=()
if [[ -n "$RS_GENOMIC_DIR" ]]; then
  [[ -d "$RS_GENOMIC_DIR" ]] || fail "HPDS_RS_GENOMIC_DIR=$RS_GENOMIC_DIR is not a directory"
  RS_GENOMIC_DIR="$(cd -P "$RS_GENOMIC_DIR" && pwd -P)"
  refuse_inside "$RS_GENOMIC_DIR" "$JAVA_STORE_DIR" "the Java store directory"
  [[ ! -d "$RS_STORE_DIR" ]] || refuse_inside "$RS_GENOMIC_DIR" "$(cd -P "$RS_STORE_DIR" && pwd -P)" "the phenotype store"
  [[ -f "$RS_GENOMIC_DIR/geno.index" ]] \
    || fail "$RS_GENOMIC_DIR has no genomic store (geno.index is missing). Build it with hpds-etl genomic-migrate or genomic-load."
  if [[ ! -f "$RS_ENV" ]]; then
    fail "$RS_ENV does not exist. With HPDS_RS_GENOMIC_DIR, write it first with hpds.genomicProcessor.impl set to the store's layout."
  fi
  if [[ -n "$(env_value "$RS_ENV" HPDS_GENOMIC_PROCESSOR_IMPL)" ]]; then
    fail "$RS_ENV sets HPDS_GENOMIC_PROCESSOR_IMPL, which hpds-rs does not read; use hpds.genomicProcessor.impl"
  fi
  if [[ -z "$(env_value "$RS_ENV" hpds.genomicProcessor.impl)" && -z "$(env_value "$RS_ENV" HPDS_GENOMICPROCESSOR_IMPL)" ]]; then
    fail "$RS_ENV sets neither hpds.genomicProcessor.impl nor HPDS_GENOMICPROCESSOR_IMPL, so the genomic store would not be served"
  fi
  genomic_args=(-v "$RS_GENOMIC_DIR:/opt/local/genomic:ro" -e HPDS_GENOMIC_DATA_DIRECTORY=/opt/local/genomic/)
fi

act mkdir -p "$RS_STORE_DIR" "$RS_RESULTS_DIR" "$RS_LOG_DIR"

if [[ ! -f "$RS_ENV" ]]; then
  id_batch_size="$(java_setting ID_BATCH_SIZE 2000 number)"
  small_threads="$(java_setting SMALL_TASK_THREADS 1 number)"
  large_threads="$(java_setting LARGE_TASK_THREADS 1 number)"
  auth_filter="$(java_setting hpds.requireAuthorizationFilter true boolean)"
  put_file "$RS_ENV" 600 <<ENV
# hpds-rs settings. Written once by aio/up.sh and never rewritten. Edit, then run aio/up.sh.
ID_BATCH_SIZE=$id_batch_size
SMALL_TASK_THREADS=$small_threads
LARGE_TASK_THREADS=$large_threads
HPDS_REQUIRE_AUTHORIZATION_FILTER=$auth_filter
HPDS_CACHE_BYTES=4294967296
RUST_LOG=info
ENV
fi

new_name="$HPDS_RS_CONTAINER-new"
old_name="$HPDS_RS_CONTAINER-old"
if container_exists "$new_name" || container_exists "$old_name"; then
  fail "$new_name or $old_name is left over from an interrupted run. Inspect them, then remove them by hand."
fi

if container_exists "$HPDS_RS_CONTAINER"; then
  create_name="$new_name"
else
  create_name="$HPDS_RS_CONTAINER"
fi

[[ "$DRY_RUN" == "1" ]] || register_container "$create_name"
act docker create --name "$create_name" --restart no --network "$PICSURE_NETWORK" \
  -v "$RS_STORE_DIR:/opt/local/hpds:ro" \
  -v "$RS_RESULTS_DIR:/var/lib/hpds-rs/results" \
  -v "$RS_LOG_DIR:/var/log/hpds-rs" \
  ${genomic_args[@]+"${genomic_args[@]}"} \
  --env-file "$RS_ENV" \
  -e HPDS_DATA_DIRECTORY=/opt/local/hpds/ \
  -e HPDS_RESULTS_DIRECTORY=/var/lib/hpds-rs/results \
  -e HPDS_LOG_DIRECTORY=/var/log/hpds-rs \
  "$HPDS_RS_IMAGE"
act docker network connect "$HPDS_NETWORK" "$create_name"
act docker start "$create_name"

if ! wait_healthy "$create_name"; then
  if [[ "$create_name" == "$new_name" ]]; then
    fail "the new $HPDS_RS_CONTAINER did not become healthy and was removed. The previous $HPDS_RS_CONTAINER is still running."
  fi
  fail "$HPDS_RS_CONTAINER did not become healthy and was removed"
fi
act docker update --restart always "$create_name"

if [[ "$create_name" == "$new_name" ]]; then
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "+ docker rename $HPDS_RS_CONTAINER $old_name; docker rename $new_name $HPDS_RS_CONTAINER"
    echo "+ keep $old_name serving its address for ${SWAP_GRACE_SECONDS}s"
    echo "+ docker stop $old_name; docker rm $old_name"
  else
    docker rename "$HPDS_RS_CONTAINER" "$old_name"
    docker rename "$new_name" "$HPDS_RS_CONTAINER"
    unregister_container "$new_name"
    register_container "$old_name"
    docker update --restart no "$old_name" >/dev/null
    log "replaced $HPDS_RS_CONTAINER; $old_name keeps serving its address for ${SWAP_GRACE_SECONDS}s"
    sleep "$SWAP_GRACE_SECONDS"
    docker stop "$old_name" >/dev/null
    docker rm "$old_name" >/dev/null
    unregister_container "$old_name"
    log "removed $old_name"
  fi
else
  unregister_container "$create_name"
fi
