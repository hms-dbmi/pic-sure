#!/usr/bin/env bash
# Creates or recreates the hpds-rs container beside the Java HPDS.
#
# Usage: aio/up.sh [--dry-run]
#
# The container joins the picsure network and the internal hpds network, restarts always,
# and is never named by start-picsure.sh, so Start PIC-SURE leaves it alone. Mounts:
#
#   $DOCKER_CONFIG_DIR/hpds-rs          -> /opt/local/hpds           (read-only)
#   $DOCKER_CONFIG_DIR/hpds-rs-results  -> /var/lib/hpds-rs/results
#   $DOCKER_CONFIG_DIR/log/hpds-rs-logs -> /var/log/hpds-rs
#
# Settings come from $DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env, written on the first run from
# the Java hpds.env (ID_BATCH_SIZE), with the authorization filter off like the all-in-one.
# Later runs never rewrite it. Fails when the networks are missing (start the AIO first) or
# the store has not been loaded (run aio/load.sh). Waits for the healthcheck.
set -euo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"
}

parse_args "$@"
[[ ${#ARGS[@]} -eq 0 ]] || { usage >&2; exit 2; }
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

act mkdir -p "$RS_STORE_DIR" "$RS_RESULTS_DIR" "$RS_LOG_DIR"

if [[ ! -f "$RS_ENV" ]]; then
  id_batch_size=2000
  if [[ -f "$JAVA_STORE_DIR/hpds.env" ]]; then
    found="$(env_value "$JAVA_STORE_DIR/hpds.env" ID_BATCH_SIZE)"
    [[ -n "$found" ]] && id_batch_size="$found"
  fi
  put_file "$RS_ENV" 600 <<ENV
# hpds-rs settings. Written once by aio/up.sh and never rewritten. Edit, then run aio/up.sh.
ID_BATCH_SIZE=$id_batch_size
HPDS_REQUIRE_AUTHORIZATION_FILTER=false
HPDS_CACHE_BYTES=4294967296
RUST_LOG=info
ENV
fi

if container_exists "$HPDS_RS_CONTAINER"; then
  act docker stop "$HPDS_RS_CONTAINER"
  act docker rm "$HPDS_RS_CONTAINER"
fi

act docker run -d --name "$HPDS_RS_CONTAINER" --restart always --network "$PICSURE_NETWORK" \
  -v "$RS_STORE_DIR:/opt/local/hpds:ro" \
  -v "$RS_RESULTS_DIR:/var/lib/hpds-rs/results" \
  -v "$RS_LOG_DIR:/var/log/hpds-rs" \
  --env-file "$RS_ENV" \
  -e HPDS_DATA_DIRECTORY=/opt/local/hpds/ \
  -e HPDS_RESULTS_DIRECTORY=/var/lib/hpds-rs/results \
  -e HPDS_LOG_DIRECTORY=/var/log/hpds-rs \
  "$HPDS_RS_IMAGE"
act docker network connect "$HPDS_NETWORK" "$HPDS_RS_CONTAINER"

wait_healthy "$HPDS_RS_CONTAINER"
