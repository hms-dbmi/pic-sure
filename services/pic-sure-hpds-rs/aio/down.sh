#!/usr/bin/env bash
# Removes the hpds-rs container, switching the query service back to Java first if needed.
# When the query service container is gone or already on Java but query.env still points at
# hpds-rs or a pre-rust backup exists (after a Docker reset, say), only the file is restored.
#
# Usage: aio/down.sh [--dry-run]
#
# Leaves the Java hpds container, the hpds-rs store, results and logs untouched.
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

switch_args=()
[[ "$DRY_RUN" == "1" ]] && switch_args+=(--dry-run)

if container_exists "$QS_CONTAINER" && [[ "$(qs_engine)" == "rust" ]]; then
  log "$QS_CONTAINER points at $HPDS_RS_CONTAINER; switching it back to Java first"
  "$AIO_DIR/switch.sh" ${switch_args[@]+"${switch_args[@]}"} java
elif [[ -f "$QUERY_ENV" ]] \
  && { [[ -n "$(find_backup)" ]] \
    || [[ "$(classify_engine "$(env_value "$QUERY_ENV" HPDS_AUTH_URL)" "$(env_value "$QUERY_ENV" HPDS_OPEN_URL)")" == "rust" ]]; }; then
  log "no running $QS_CONTAINER points at $HPDS_RS_CONTAINER, but query.env still has the rust setup; restoring the file only"
  "$AIO_DIR/switch.sh" ${switch_args[@]+"${switch_args[@]}"} --file-only java
fi

if container_exists "$HPDS_RS_CONTAINER"; then
  act docker stop "$HPDS_RS_CONTAINER"
  act docker rm "$HPDS_RS_CONTAINER"
else
  log "$HPDS_RS_CONTAINER does not exist; nothing to remove"
fi
