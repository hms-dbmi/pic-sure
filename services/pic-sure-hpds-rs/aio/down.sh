#!/usr/bin/env bash
# Removes the hpds-rs container, switching the query service back to Java first if needed.
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

if container_exists "$QS_CONTAINER" && [[ "$(qs_engine)" == "rust" ]]; then
  log "$QS_CONTAINER points at $HPDS_RS_CONTAINER; switching it back to Java first"
  switch_args=()
  [[ "$DRY_RUN" == "1" ]] && switch_args+=(--dry-run)
  "$AIO_DIR/switch.sh" ${switch_args[@]+"${switch_args[@]}"} java
fi

if container_exists "$HPDS_RS_CONTAINER"; then
  act docker stop "$HPDS_RS_CONTAINER"
  act docker rm "$HPDS_RS_CONTAINER"
else
  log "$HPDS_RS_CONTAINER does not exist; nothing to remove"
fi
