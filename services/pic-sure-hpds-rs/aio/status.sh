#!/usr/bin/env bash
# Shows which HPDS engine the query service uses and how both engines are doing.
#
# Usage: aio/status.sh
#
# The engine is read from the environment of the running query service container, not
# from query.env, because the container only re-reads the file when it is recreated. The
# two are compared, and a mismatch is flagged. The concept count is the row count of
# hpds-rs/columnMeta.csv.
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

if container_exists "$QS_CONTAINER"; then
  running_engine="$(qs_engine)"
  echo "query service $QS_CONTAINER: $(container_running "$QS_CONTAINER" && echo running || echo stopped)"
  echo "  running container HPDS_AUTH_URL=$(container_env_value "$QS_CONTAINER" HPDS_AUTH_URL)"
  echo "  running container HPDS_OPEN_URL=$(container_env_value "$QS_CONTAINER" HPDS_OPEN_URL)"
  echo "  engine in use: $running_engine"
else
  running_engine=""
  echo "query service $QS_CONTAINER: absent"
fi

if [[ -f "$QUERY_ENV" ]]; then
  file_engine="$(classify_engine "$(env_value "$QUERY_ENV" HPDS_AUTH_URL)" "$(env_value "$QUERY_ENV" HPDS_OPEN_URL)")"
  echo "query.env points at: $file_engine"
  if [[ -n "$running_engine" && "$running_engine" != "$file_engine" ]]; then
    echo "  WARNING: the running container and query.env disagree; the next Start PIC-SURE moves the query service to $file_engine"
  fi
else
  echo "query.env: absent"
fi
[[ -f "$QUERY_ENV_BACKUP" ]] && echo "backup: $QUERY_ENV_BACKUP"
[[ -f "$QUERY_ENV_LEGACY_BACKUP" ]] && echo "backup (earlier name): $QUERY_ENV_LEGACY_BACKUP"

echo "java hpds $HPDS_JAVA_CONTAINER: $(container_health "$HPDS_JAVA_CONTAINER")"
echo "hpds-rs $HPDS_RS_CONTAINER: $(container_health "$HPDS_RS_CONTAINER")"

if [[ -f "$RS_STORE_DIR/columnMeta.csv" ]]; then
  echo "hpds-rs store: $(wc -l < "$RS_STORE_DIR/columnMeta.csv" | tr -d ' ') concepts in $RS_STORE_DIR"
else
  echo "hpds-rs store: none in $RS_STORE_DIR"
fi
if [[ -f "$JAVA_STORE_DIR/columnMeta.csv" ]]; then
  echo "java store: $(wc -l < "$JAVA_STORE_DIR/columnMeta.csv" | tr -d ' ') concepts in $JAVA_STORE_DIR"
fi
