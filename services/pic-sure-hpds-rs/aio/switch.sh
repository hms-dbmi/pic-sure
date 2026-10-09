#!/usr/bin/env bash
# Points the query service at hpds-rs or back at the Java HPDS.
#
# Usage: aio/switch.sh [--dry-run] [--file-only] rust|java
#
#   rust   refuses unless hpds-rs is healthy. Copies query/query.env to
#          query/query.env.pre-rust once (an existing backup is never overwritten), sets
#          HPDS_AUTH_URL and HPDS_OPEN_URL to http://hpds-rs:8080/PIC-SURE (every other line
#          is kept byte for byte), then recreates the query service container.
#   java   puts HPDS_AUTH_URL and HPDS_OPEN_URL back to the values in the backup, each key
#          on its own, retires the backup to query.env.pre-rust.used, and recreates the
#          query service. A query.env.java-backup from an earlier version of these scripts
#          is read when query.env.pre-rust is absent.
#
# --file-only restores query.env without touching any container. down.sh uses it when no
# query service container points at hpds-rs.
#
# Every check and the creation of the replacement container happen before query.env is
# touched. If the replacement then fails to start, the previous container is running again
# and the URL lines (and the new backup) are restored, so the file matches the running
# container. See lib.sh prepare_qs_recreation and commit_qs_recreation.
set -euo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"
}

FILE_ONLY=0

# Warns for each backend URL key whose current value is neither the hpds-rs URL nor the
# value held by backup file $1.
warn_if_backup_differs() {
  local backup="$1" key current saved
  for key in HPDS_AUTH_URL HPDS_OPEN_URL; do
    current="$(env_value "$QUERY_ENV" "$key")"
    saved="$(env_value "$backup" "$key")"
    if [[ "$current" != "$(rs_backend_url)" && "$current" != "$saved" ]]; then
      echo "warning: $key in query.env is $current but $backup holds $saved; switching to java restores the backup's value" >&2
    fi
  done
}

# Sets HPDS_AUTH_URL to $1 and HPDS_OPEN_URL to $2 in query.env and recreates the query
# service, in the order that keeps the file and the running container in agreement: checks
# and the replacement container first, then the file, then the swap. When the swap fails the
# previous container is already running again and the file is restored from a snapshot.
# $3 is the backup file to remove again on failure (empty for none).
apply_urls() {
  local auth="$1" open="$2" created_backup="$3" candidate snapshot
  candidate="$(mktemp "${TMPDIR:-/tmp}/query-env-new.XXXXXX")"
  register_temp_file "$candidate"
  snapshot="$(mktemp "${TMPDIR:-/tmp}/query-env-old.XXXXXX")"
  register_temp_file "$snapshot"
  cat "$QUERY_ENV" > "$snapshot"
  if ! rewrite_backend_urls "$QUERY_ENV" "$auth" "$open" > "$candidate"; then
    fail "$QUERY_ENV must contain exactly one HPDS_AUTH_URL= and one HPDS_OPEN_URL= line. Nothing was changed."
  fi

  if [[ "$FILE_ONLY" != "1" ]]; then
    prepare_qs_recreation "$candidate"
  fi

  if [[ -n "$created_backup" ]]; then
    act cp -p "$QUERY_ENV" "$created_backup"
  fi
  [[ "$DRY_RUN" == "1" ]] || arm_file_restore "$QUERY_ENV" "$snapshot" "$created_backup"
  replace_contents "$QUERY_ENV" < "$candidate"

  if [[ "$FILE_ONLY" != "1" ]] && ! commit_qs_recreation; then
    restore_armed_file
    fail "$QS_CONTAINER did not come up with the new URLs. The previous container is running again and query.env was restored to match it."
  fi
  disarm_file_restore
}

switch_rust() {
  local health backup created=""
  health="$(container_health "$HPDS_RS_CONTAINER")"
  if [[ "$health" != "healthy" ]]; then
    if [[ "$DRY_RUN" == "1" ]]; then
      echo "note: $HPDS_RS_CONTAINER is $health; a real run would refuse"
    else
      fail "$HPDS_RS_CONTAINER is $health, not healthy. Run aio/up.sh first. Nothing was changed."
    fi
  fi
  backup="$(find_backup)"
  if [[ -n "$backup" ]]; then
    log "keeping the existing backup $backup"
    warn_if_backup_differs "$backup"
  else
    created="$QUERY_ENV_BACKUP"
  fi
  apply_urls "$(rs_backend_url)" "$(rs_backend_url)" "$created"
  if [[ "$backup" == "$QUERY_ENV_LEGACY_BACKUP" ]]; then
    act mv -f "$QUERY_ENV_LEGACY_BACKUP" "$QUERY_ENV_BACKUP"
  fi
}

switch_java() {
  local backup auth open
  backup="$(find_backup)"
  if [[ -n "$backup" ]]; then
    auth="$(env_value "$backup" HPDS_AUTH_URL)"
    open="$(env_value "$backup" HPDS_OPEN_URL)"
    [[ -n "$auth" && -n "$open" ]] || fail "$backup does not hold both HPDS_AUTH_URL and HPDS_OPEN_URL. Nothing was changed."
    if [[ "$auth" == "$(rs_backend_url)" || "$open" == "$(rs_backend_url)" ]]; then
      fail "$backup points at $HPDS_RS_CONTAINER, so it is not a pre-rust backup. Edit HPDS_AUTH_URL and HPDS_OPEN_URL by hand. Nothing was changed."
    fi
    warn_if_backup_differs "$backup"
    apply_urls "$auth" "$open" ""
    act mv -f "$backup" "$backup.used"
  else
    [[ "$(classify_engine "$(env_value "$QUERY_ENV" HPDS_AUTH_URL)" "$(env_value "$QUERY_ENV" HPDS_OPEN_URL)")" == "java" ]] \
      || fail "no backup $QUERY_ENV_BACKUP and query.env does not point at the Java HPDS. Edit HPDS_AUTH_URL and HPDS_OPEN_URL by hand."
    log "query.env already points at the Java HPDS"
    apply_urls "$(env_value "$QUERY_ENV" HPDS_AUTH_URL)" "$(env_value "$QUERY_ENV" HPDS_OPEN_URL)" ""
  fi
}

parse_args "$@"
filtered=()
for arg in ${ARGS[@]+"${ARGS[@]}"}; do
  if [[ "$arg" == "--file-only" ]]; then FILE_ONLY=1; else filtered+=("$arg"); fi
done
[[ ${#filtered[@]} -eq 1 ]] || { usage >&2; exit 2; }
target="${filtered[0]}"
require_config_dir
require_docker
[[ -f "$QUERY_ENV" ]] || fail "$QUERY_ENV does not exist"

case "$target" in
  rust) switch_rust ;;
  java) switch_java ;;
  *) usage >&2; exit 2 ;;
esac
