#!/usr/bin/env bash
# Points the query service at hpds-rs or back at the Java HPDS.
#
# Usage: aio/switch.sh [--dry-run] rust|java
#
#   rust   refuses unless hpds-rs is healthy. Copies query/query.env to
#          query/query.env.java-backup once (an existing backup is never overwritten), sets
#          HPDS_AUTH_URL and HPDS_OPEN_URL to http://hpds-rs:8080/PIC-SURE (every other line
#          is kept byte for byte), then recreates the query service container.
#   java   puts HPDS_AUTH_URL and HPDS_OPEN_URL back to the values in the backup, retires
#          the backup to query.env.java-backup.used, and recreates the query service.
#
# The recreation follows lib.sh recreate_qs: the new container is built from docker inspect
# of the old one with its environment re-read from query.env, and the old one is restored if
# the new one does not come up.
set -euo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"
}

# Sets the two backend URLs in query.env to $1, keeping every other line.
set_urls() {
  local url="$1" rewritten
  rewritten="$(mktemp "${TMPDIR:-/tmp}/query-env.XXXXXX")"
  if ! rewrite_backend_urls "$QUERY_ENV" "$url" > "$rewritten"; then
    rm -f "$rewritten"
    fail "$QUERY_ENV must contain exactly one HPDS_AUTH_URL= and one HPDS_OPEN_URL= line"
  fi
  replace_contents "$QUERY_ENV" < "$rewritten"
  rm -f "$rewritten"
}

switch_rust() {
  local health
  health="$(container_health "$HPDS_RS_CONTAINER")"
  if [[ "$health" != "healthy" ]]; then
    if [[ "$DRY_RUN" == "1" ]]; then
      echo "note: $HPDS_RS_CONTAINER is $health; a real run would refuse"
    else
      fail "$HPDS_RS_CONTAINER is $health, not healthy. Run aio/up.sh first."
    fi
  fi
  if [[ -e "$QUERY_ENV_BACKUP" ]]; then
    log "keeping the existing backup $QUERY_ENV_BACKUP"
  else
    act cp -p "$QUERY_ENV" "$QUERY_ENV_BACKUP"
  fi
  set_urls "$(rs_backend_url)"
  recreate_qs
}

switch_java() {
  local auth open
  if [[ -f "$QUERY_ENV_BACKUP" ]]; then
    auth="$(env_value "$QUERY_ENV_BACKUP" HPDS_AUTH_URL)"
    open="$(env_value "$QUERY_ENV_BACKUP" HPDS_OPEN_URL)"
    [[ -n "$auth" && "$auth" == "$open" ]] || fail "$QUERY_ENV_BACKUP does not hold one backend URL for both keys"
    set_urls "$auth"
    act mv -f "$QUERY_ENV_BACKUP" "$QUERY_ENV_BACKUP.used"
  else
    [[ "$(classify_engine "$(env_value "$QUERY_ENV" HPDS_AUTH_URL)" "$(env_value "$QUERY_ENV" HPDS_OPEN_URL)")" == "java" ]] \
      || fail "no backup $QUERY_ENV_BACKUP and query.env does not point at the Java HPDS. Edit HPDS_AUTH_URL and HPDS_OPEN_URL by hand."
    log "query.env already points at the Java HPDS"
  fi
  recreate_qs
}

parse_args "$@"
[[ ${#ARGS[@]} -eq 1 ]] || { usage >&2; exit 2; }
target="${ARGS[0]}"
require_config_dir
require_docker
[[ -f "$QUERY_ENV" ]] || fail "$QUERY_ENV does not exist"

case "$target" in
  rust) switch_rust ;;
  java) switch_java ;;
  *) usage >&2; exit 2 ;;
esac
