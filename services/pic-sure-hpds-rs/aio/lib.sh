#!/usr/bin/env bash
# shellcheck disable=SC2034
# Shared helpers for the hpds-rs all-in-one scripts. Sourced, never run.
#
# Every script reads DOCKER_CONFIG_DIR from the environment and accepts --dry-run, which
# prints each mutating docker command and file write instead of performing it. Read-only
# docker queries (inspect, image inspect) still run in a dry run.
#
# Env overrides, mainly for tests on throwaway networks:
#   PICSURE_NETWORK     external network of the stack (picsure)
#   HPDS_NETWORK        internal network the HPDS containers share (hpds)
#   QS_CONTAINER        query service container (pic-sure-hpds-query-service)
#   HPDS_RS_CONTAINER   the Rust HPDS container (hpds-rs)
#   HPDS_JAVA_CONTAINER the Java HPDS container (hpds)
#   HPDS_RS_IMAGE       hms-dbmi/pic-sure-hpds-rs:LATEST
#   RUST_ETL_IMAGE      hms-dbmi/pic-sure-hpds-rs-etl:LATEST
#   JAVA_ETL_IMAGE      Java ETL image holding ExportForMigration (found automatically)
#   HEALTH_TIMEOUT      seconds to wait for a container to become healthy (180)
#   STABILITY_SECONDS   seconds a container without a healthcheck must stay running (5)
#   HPDS_RS_SWAP_GRACE_SECONDS  seconds up.sh keeps a replaced hpds-rs serving its old address (60)

AIO_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
RS_ROOT="$(cd -P "$AIO_DIR/.." && pwd -P)"

PICSURE_NETWORK="${PICSURE_NETWORK:-picsure}"
HPDS_NETWORK="${HPDS_NETWORK:-hpds}"
QS_CONTAINER="${QS_CONTAINER:-pic-sure-hpds-query-service}"
HPDS_RS_CONTAINER="${HPDS_RS_CONTAINER:-hpds-rs}"
HPDS_JAVA_CONTAINER="${HPDS_JAVA_CONTAINER:-hpds}"
HPDS_RS_IMAGE="${HPDS_RS_IMAGE:-hms-dbmi/pic-sure-hpds-rs:LATEST}"
RUST_ETL_IMAGE="${RUST_ETL_IMAGE:-hms-dbmi/pic-sure-hpds-rs-etl:LATEST}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
STABILITY_SECONDS="${STABILITY_SECONDS:-5}"
BACKEND_PATH="/PIC-SURE"

DRY_RUN=0
ARGS=()

fail() {
  echo "$(basename "$0"): $*" >&2
  exit 1
}

log() {
  echo "$(basename "$0"): $*"
}

CLEANUP_FILES=()
CLEANUP_CONTAINERS=()
SWAP_ACTIVE=0
SWAP_NAME=""
SWAP_OLD_NAME=""
RESTORE_TARGET=""
RESTORE_SOURCE=""
RESTORE_REMOVE=""

# Remembers that file $1 must be put back from the snapshot $2 (and file $3, when given,
# removed) if the process exits before disarm_file_restore.
arm_file_restore() {
  RESTORE_TARGET="$1"
  RESTORE_SOURCE="$2"
  RESTORE_REMOVE="${3:-}"
}

# Forgets the pending restore, keeping the changes.
disarm_file_restore() {
  RESTORE_TARGET=""
  RESTORE_SOURCE=""
  RESTORE_REMOVE=""
}

# Puts the armed file back from its snapshot, removes the file registered with it, and
# disarms.
restore_armed_file() {
  [[ -n "$RESTORE_TARGET" ]] || return 0
  cat "$RESTORE_SOURCE" > "$RESTORE_TARGET"
  [[ -z "$RESTORE_REMOVE" ]] || rm -f "$RESTORE_REMOVE"
  disarm_file_restore
}

# Registers the temporary file $1 for removal on every exit path.
register_temp_file() {
  CLEANUP_FILES+=("$1")
}

# Registers container $1 for forced removal on every exit path until unregister_container.
register_container() {
  CLEANUP_CONTAINERS+=("$1")
}

# Stops tracking container $1 once it has been renamed into service or removed.
unregister_container() {
  local kept=() entry
  for entry in ${CLEANUP_CONTAINERS[@]+"${CLEANUP_CONTAINERS[@]}"}; do
    [[ "$entry" == "$1" ]] || kept+=("$entry")
  done
  CLEANUP_CONTAINERS=(${kept[@]+"${kept[@]}"})
}

# Puts a container swap that stopped $SWAP_NAME back: removes whatever now holds the name
# when the previous container was renamed to $SWAP_OLD_NAME, renames it back and starts it.
# Starts the original in place when it was only stopped.
rollback_swap() {
  [[ "$SWAP_ACTIVE" == "1" ]] || return 0
  if container_exists "$SWAP_OLD_NAME"; then
    docker rm -f "$SWAP_NAME" >/dev/null 2>&1 || true
    docker rename "$SWAP_OLD_NAME" "$SWAP_NAME" >/dev/null 2>&1 || true
  fi
  if container_exists "$SWAP_NAME" && ! container_running "$SWAP_NAME"; then
    docker start "$SWAP_NAME" >/dev/null 2>&1 || true
  fi
  SWAP_ACTIVE=0
}

# EXIT handler: removes registered temporary files (they can hold secrets), registered
# half-created containers, and restores an interrupted container swap.
run_cleanup() {
  local entry
  rollback_swap
  restore_armed_file
  for entry in ${CLEANUP_CONTAINERS[@]+"${CLEANUP_CONTAINERS[@]}"}; do
    docker rm -f "$entry" >/dev/null 2>&1 || true
  done
  CLEANUP_CONTAINERS=()
  for entry in ${CLEANUP_FILES[@]+"${CLEANUP_FILES[@]}"}; do
    rm -f "$entry"
  done
  CLEANUP_FILES=()
}

trap run_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

# Sets DRY_RUN and ARGS from the command line. --dry-run may appear anywhere.
parse_args() {
  DRY_RUN="${DRY_RUN_ENV:-0}"
  ARGS=()
  local arg
  for arg in "$@"; do
    case "$arg" in
      --dry-run) DRY_RUN=1 ;;
      -h|--help) usage; exit 0 ;;
      *) ARGS+=("$arg") ;;
    esac
  done
}

# Fails unless DOCKER_CONFIG_DIR is set to an existing directory, then derives the paths
# every script uses.
require_config_dir() {
  [[ -n "${DOCKER_CONFIG_DIR:-}" ]] || fail "DOCKER_CONFIG_DIR is not set. Export the all-in-one config directory first."
  [[ -d "$DOCKER_CONFIG_DIR" ]] || fail "DOCKER_CONFIG_DIR=$DOCKER_CONFIG_DIR is not a directory"
  DOCKER_CONFIG_DIR="$(cd -P "$DOCKER_CONFIG_DIR" && pwd -P)"
  JAVA_STORE_DIR="$DOCKER_CONFIG_DIR/hpds"
  RS_STORE_DIR="$DOCKER_CONFIG_DIR/hpds-rs"
  RS_RESULTS_DIR="$DOCKER_CONFIG_DIR/hpds-rs-results"
  RS_LOG_DIR="$DOCKER_CONFIG_DIR/log/hpds-rs-logs"
  CSV_INPUT_DIR="$DOCKER_CONFIG_DIR/hpds_input"
  QUERY_ENV="$DOCKER_CONFIG_DIR/query/query.env"
  QUERY_ENV_BACKUP="$QUERY_ENV.pre-rust"
  QUERY_ENV_LEGACY_BACKUP="$QUERY_ENV.java-backup"
  RS_ENV="$RS_STORE_DIR/hpds-rs.env"
}

# Runs a command, or only prints it under --dry-run.
act() {
  if [[ "$DRY_RUN" == "1" ]]; then
    printf '+'
    printf ' %q' "$@"
    printf '\n'
  else
    "$@"
  fi
}

# Writes stdin to the file $1 with mode $2. Under --dry-run prints the target and the
# content instead.
put_file() {
  local target="$1" mode="$2"
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "+ write $target (mode $mode)"
    sed 's/^/    | /'
  else
    local tmp
    tmp="$(mktemp "$target.XXXXXX")"
    cat > "$tmp"
    chmod "$mode" "$tmp"
    mv -f "$tmp" "$target"
  fi
}

require_docker() {
  command -v docker >/dev/null 2>&1 || fail "docker is not on the PATH"
  command -v jq >/dev/null 2>&1 || fail "jq is not on the PATH"
  docker info >/dev/null 2>&1 || fail "the docker daemon is not reachable"
}

container_exists() {
  docker container inspect "$1" >/dev/null 2>&1
}

container_running() {
  [[ "$(docker container inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" == "true" ]]
}

# Prints the health status of container $1: starting, healthy, unhealthy, none (no
# healthcheck), or absent.
container_health() {
  if ! container_exists "$1"; then
    echo absent
    return 0
  fi
  docker container inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$1"
}

# Fails with "start the AIO first" unless both networks exist. Under --dry-run it only warns.
require_networks() {
  local network
  for network in "$PICSURE_NETWORK" "$HPDS_NETWORK"; do
    if ! docker network inspect "$network" >/dev/null 2>&1; then
      if [[ "$DRY_RUN" == "1" ]]; then
        echo "note: network $network does not exist; a real run would stop here (start the AIO first)"
      else
        fail "docker network $network does not exist. Start the AIO first (Jenkins \"Start PIC-SURE\" or start-picsure.sh)."
      fi
    fi
  done
}

# Prints the restart count of container $1.
container_restart_count() {
  docker container inspect -f '{{.RestartCount}}' "$1" 2>/dev/null || echo 0
}

# Waits until container $1 reports healthy, or returns 1 with a message after HEALTH_TIMEOUT
# seconds, on unhealthy, or when the container stops or restarts. A container without a
# healthcheck must stay running, without restarting, for STABILITY_SECONDS. Returns instead
# of exiting so callers can roll back.
wait_healthy() {
  local name="$1" waited=0 state status restarts_at_start
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "+ wait up to ${HEALTH_TIMEOUT}s for $name to report healthy"
    return 0
  fi
  restarts_at_start="$(container_restart_count "$name")"
  while true; do
    state="$(container_health "$name")"
    status="$(docker container inspect -f '{{.State.Status}}' "$name" 2>/dev/null || echo absent)"
    case "$state" in
      healthy) log "$name is healthy"; return 0 ;;
      unhealthy)
        docker logs --tail 30 "$name" >&2 || true
        echo "$(basename "$0"): $name is unhealthy" >&2
        return 1
        ;;
      absent)
        echo "$(basename "$0"): $name does not exist" >&2
        return 1
        ;;
    esac
    if [[ "$status" != "running" && "$status" != "created" ]] \
      || [[ "$(container_restart_count "$name")" != "$restarts_at_start" ]]; then
      docker logs --tail 30 "$name" >&2 || true
      echo "$(basename "$0"): $name is not staying up (status: $status)" >&2
      return 1
    fi
    if [[ "$state" == "none" && "$waited" -ge "$STABILITY_SECONDS" && "$status" == "running" ]]; then
      log "$name is running (no healthcheck, stable for ${STABILITY_SECONDS}s)"
      return 0
    fi
    if [[ "$waited" -ge "$HEALTH_TIMEOUT" ]]; then
      docker logs --tail 30 "$name" >&2 || true
      echo "$(basename "$0"): $name was not healthy after ${HEALTH_TIMEOUT}s (state: $state)" >&2
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
}

# Prints the value of KEY $2 in env file $1 (the last assignment wins), or nothing.
env_value() {
  awk -v key="$2" '
    /^[[:space:]]*#/ { next }
    index($0, "=") == 0 { next }
    { k = substr($0, 1, index($0, "=") - 1); if (k == key) v = substr($0, index($0, "=") + 1) }
    END { print v }
  ' "$1"
}

# Prints the value of KEY $1 in the Java HPDS hpds.env, or nothing when the file or key is absent.
java_env_value() {
  [[ -f "$JAVA_STORE_DIR/hpds.env" ]] || return 0
  env_value "$JAVA_STORE_DIR/hpds.env" "$1"
}

# Prints the value of environment variable $2 as set in the container $1, or nothing.
container_env_value() {
  docker container inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" 2>/dev/null \
    | awk -v key="$2" 'index($0, "=") { k = substr($0, 1, index($0, "=") - 1); if (k == key) v = substr($0, index($0, "=") + 1) } END { print v }'
}

rs_backend_url() {
  echo "http://$HPDS_RS_CONTAINER:8080$BACKEND_PATH"
}

java_backend_url() {
  echo "http://$HPDS_JAVA_CONTAINER:8080$BACKEND_PATH"
}

# Prints rust, java or other for the HPDS_AUTH_URL and HPDS_OPEN_URL values $1 and $2.
classify_engine() {
  local auth="$1" open="$2"
  if [[ "$auth" == "$(rs_backend_url)" && "$open" == "$(rs_backend_url)" ]]; then
    echo rust
  elif [[ "$auth" == "$(java_backend_url)" && "$open" == "$(java_backend_url)" ]]; then
    echo java
  else
    echo other
  fi
}

# Prints the engine the running query service container points at, from its environment.
qs_engine() {
  classify_engine "$(container_env_value "$QS_CONTAINER" HPDS_AUTH_URL)" "$(container_env_value "$QS_CONTAINER" HPDS_OPEN_URL)"
}

# Prints the first Java ETL image that holds ExportForMigration: JAVA_ETL_IMAGE when set,
# else the :migrate, :bench and :LATEST tags in that order. Prints nothing when none does.
find_java_etl_image() {
  local image
  if [[ -n "${JAVA_ETL_IMAGE:-}" ]]; then
    echo "$JAVA_ETL_IMAGE"
    return 0
  fi
  for image in hms-dbmi/pic-sure-hpds-etl:migrate hms-dbmi/pic-sure-hpds-etl:bench hms-dbmi/pic-sure-hpds-etl:LATEST; do
    if docker image inspect "$image" >/dev/null 2>&1 \
      && docker run --rm --entrypoint test "$image" -f /app/ExportForMigration-jar-with-dependencies.jar >/dev/null 2>&1; then
      echo "$image"
      return 0
    fi
  done
}

# Prints the backup file that holds the pre-rust query.env: query.env.pre-rust, else a
# query.env.java-backup left by an earlier version of these scripts. Prints nothing when
# neither exists.
find_backup() {
  if [[ -f "$QUERY_ENV_BACKUP" ]]; then
    echo "$QUERY_ENV_BACKUP"
  elif [[ -f "$QUERY_ENV_LEGACY_BACKUP" ]]; then
    echo "$QUERY_ENV_LEGACY_BACKUP"
  fi
}

# Rewrites the HPDS_AUTH_URL line of env file $1 to the URL $2 and the HPDS_OPEN_URL line to
# the URL $3 (the other lines are copied byte for byte) and prints the result to stdout. The
# URLs reach awk through the environment so backslashes survive. Fails when a key is missing
# or repeated.
rewrite_backend_urls() {
  local file="$1"
  NEW_AUTH_URL="$2" NEW_OPEN_URL="$3" awk '
    /^HPDS_AUTH_URL=/ { print "HPDS_AUTH_URL=" ENVIRON["NEW_AUTH_URL"]; auth++; next }
    /^HPDS_OPEN_URL=/ { print "HPDS_OPEN_URL=" ENVIRON["NEW_OPEN_URL"]; open++; next }
    { print }
    END { if (auth != 1 || open != 1) exit 3 }
  ' "$file"
}

# Writes file $1 from stdin without changing its inode or mode, so a later reader sees a
# complete file.
replace_contents() {
  local target="$1" tmp
  tmp="$(mktemp "${TMPDIR:-/tmp}/aio-replace.XXXXXX")"
  register_temp_file "$tmp"
  cat > "$tmp"
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "+ rewrite $target; changed lines:"
    diff "$target" "$tmp" | sed -n 's/^\([<>]\) \(HPDS_[A-Z_]*=\)\(.*\)$/    \1 \2\3/p' || true
  else
    cat "$tmp" > "$target"
  fi
  rm -f "$tmp"
}

QS_REPLACEMENT=""
QS_OLD_NAME=""

# Runs every check and builds the replacement of the query service container $QS_CONTAINER,
# without stopping or renaming anything and without reading query.env.
#
# The replacement is created, not started, under the temporary name $QS_CONTAINER-switch-new.
# It is built from `docker inspect` of the running container: image (by name while the name
# still resolves to the same image id, else by id), restart policy, networks and aliases,
# mounts, ports and labels. Its environment is the old container's own variables (those the
# image does not define) overridden by every key in the env file $1. The merged environment
# lives in a temporary file removed as soon as docker create has read it, and on every exit
# path.
#
# Sets QS_REPLACEMENT to the temporary name, or leaves it empty when the container does not
# exist. Containers carrying settings this does not reproduce (a changed entrypoint, user,
# resource limits, devices, extra capabilities, tmpfs, init, ulimits and similar) are refused so
# nothing is dropped silently. Exits through fail when a check fails or docker rejects the
# configuration; the half-created replacement is removed by the exit handler.
prepare_qs_recreation() {
  local candidate_env="$1" name="$QS_CONTAINER" new_name old_name
  new_name="$name-switch-new"
  old_name="$name-switch-old"
  QS_REPLACEMENT=""
  QS_OLD_NAME="$old_name"

  if ! container_exists "$name"; then
    echo "note: container $name does not exist; run Start PIC-SURE to create it from query.env"
    return 0
  fi
  if container_exists "$new_name" || container_exists "$old_name"; then
    fail "$new_name or $old_name is left over from an interrupted run. Inspect them, then remove them by hand."
  fi

  local inspect image_id image_inspect reasons line
  inspect="$(docker container inspect "$name")"
  image_id="$(jq -r '.[0].Image' <<<"$inspect")"
  image_inspect="$(docker image inspect "$image_id")"

  reasons="$(jq -r --argjson img "$image_inspect" -f "$AIO_DIR/recreate-refuse.jq" <<<"$inspect")"
  if [[ -n "$reasons" ]]; then
    echo "$name carries settings this script does not reproduce:" >&2
    while IFS= read -r line; do echo "  - $line" >&2; done <<<"$reasons"
    fail "run Start PIC-SURE to recreate $name from query.env instead. query.env was not changed"
  fi

  local env_file
  env_file="$(mktemp "${TMPDIR:-/tmp}/qs-env.XXXXXX")"
  register_temp_file "$env_file"
  build_merged_env "$inspect" "$image_inspect" "$candidate_env" > "$env_file"
  report_env_changes "$inspect" "$env_file"

  local create_args=()
  while IFS= read -r line; do
    create_args+=("$line")
  done < <(jq -r --argjson img "$image_inspect" -f "$AIO_DIR/recreate-args.jq" <<<"$inspect")

  local first_network extra_networks=()
  first_network="$(jq -r --arg preferred "$PICSURE_NETWORK" '.[0].NetworkSettings.Networks | keys | (if index($preferred) then $preferred else .[0] end) // empty' <<<"$inspect")"
  [[ -n "$first_network" ]] || fail "$name is attached to no network. query.env was not changed"
  while IFS= read -r line; do
    [[ -n "$line" ]] && extra_networks+=("$line")
  done < <(jq -r --arg first "$first_network" '.[0].NetworkSettings.Networks | keys[] | select(. != $first)' <<<"$inspect")

  local first_aliases=() alias
  while IFS= read -r alias; do
    [[ -n "$alias" ]] && first_aliases+=(--network-alias "$alias")
  done < <(network_aliases "$inspect" "$first_network")

  local image_ref
  image_ref="$(jq -r '.[0].Config.Image' <<<"$inspect")"
  if [[ "$(docker image inspect -f '{{.Id}}' "$image_ref" 2>/dev/null || true)" != "$image_id" ]]; then
    image_ref="$image_id"
  fi

  [[ "$DRY_RUN" == "1" ]] || register_container "$new_name"
  if ! act docker create --name "$new_name" --network "$first_network" ${first_aliases[@]+"${first_aliases[@]}"} \
    ${create_args[@]+"${create_args[@]}"} --env-file "$env_file" "$image_ref"; then
    fail "docker rejected the replacement for $name. query.env was not changed and $name is untouched"
  fi
  rm -f "$env_file"

  local extra extra_aliases
  for extra in ${extra_networks[@]+"${extra_networks[@]}"}; do
    extra_aliases=()
    while IFS= read -r alias; do
      [[ -n "$alias" ]] && extra_aliases+=(--alias "$alias")
    done < <(network_aliases "$inspect" "$extra")
    if ! act docker network connect ${extra_aliases[@]+"${extra_aliases[@]}"} "$extra" "$new_name"; then
      fail "could not attach the replacement for $name to network $extra. query.env was not changed and $name is untouched"
    fi
  done
  QS_REPLACEMENT="$new_name"
}

# Swaps the replacement made by prepare_qs_recreation into service: stops the old container,
# renames it aside, gives the replacement its name, starts it and waits for it to be healthy.
# The old container is removed only after that. Does nothing when no replacement exists.
#
# Returns 1, after putting the previous container back under its name and running, when the
# replacement does not come up. The caller then restores query.env.
commit_qs_recreation() {
  local name="$QS_CONTAINER" new_name="$QS_REPLACEMENT" old_name="$QS_OLD_NAME"
  [[ -n "$new_name" ]] || return 0

  if [[ "$DRY_RUN" == "1" ]]; then
    echo "+ docker stop $name; docker rename $name $old_name; docker rename $new_name $name; docker start $name"
    echo "+ on failure: docker rm -f $name; docker rename $old_name $name; docker start $name"
    echo "+ docker rm $old_name"
    return 0
  fi

  SWAP_NAME="$name"
  SWAP_OLD_NAME="$old_name"
  SWAP_ACTIVE=1
  docker stop "$name" >/dev/null
  docker rename "$name" "$old_name"
  docker rename "$new_name" "$name"
  unregister_container "$new_name"
  if ! docker start "$name" >/dev/null || ! wait_healthy "$name"; then
    echo "$name failed to start; restoring the previous container" >&2
    rollback_swap
    return 1
  fi
  docker rm "$old_name" >/dev/null
  SWAP_ACTIVE=0
  log "recreated $name"
}

# Prints the extra network aliases of container inspect $1 on network $2, leaving out the
# container name and the short container id docker adds itself.
network_aliases() {
  jq -r --arg net "$2" '
    .[0] as $c
    | ($c.NetworkSettings.Networks[$net].Aliases // [])[]
    | . as $alias
    | select($alias != ($c.Name | ltrimstr("/")) and (($c.Id | startswith($alias)) | not))
  ' <<<"$1"
}

# Prints the merged environment for the recreated container: the old container's own
# variables (those not defined by the image) whose keys query.env does not set, then every
# KEY=VALUE line of query.env.
build_merged_env() {
  local inspect="$1" image_inspect="$2" env_file="$3" old
  old="$(mktemp "${TMPDIR:-/tmp}/qs-old-env.XXXXXX")"
  register_temp_file "$old"
  jq -r --argjson img "$image_inspect" '.[0].Config.Env - ($img[0].Config.Env // []) | .[]' <<<"$inspect" > "$old"
  awk '
    NR == FNR {
      if ($0 ~ /^[[:space:]]*#/ || index($0, "=") == 0) next
      seen[substr($0, 1, index($0, "=") - 1)] = 1
      next
    }
    { if (!(substr($0, 1, index($0, "=") - 1) in seen)) print }
  ' "$env_file" "$old"
  awk '!/^[[:space:]]*#/ && index($0, "=") > 0' "$env_file"
  rm -f "$old"
}

# Prints the names of environment variables whose value differs between the running
# container $1 (inspect JSON) and the merged env file $2. Values are shown only for the
# two backend URLs.
report_env_changes() {
  local inspect="$1" merged="$2" key old_value new_value
  while IFS= read -r key; do
    old_value="$(jq -r --arg k "$key" '.[0].Config.Env[] | select(startswith($k + "=")) | sub("^[^=]*="; "")' <<<"$inspect" | tail -n 1)"
    new_value="$(env_value "$merged" "$key")"
    if [[ "$old_value" != "$new_value" ]]; then
      case "$key" in
        HPDS_AUTH_URL|HPDS_OPEN_URL) echo "env change: $key: $old_value -> $new_value" ;;
        *) echo "env change: $key (value differs)" ;;
      esac
    fi
  done < <(awk '!/^[[:space:]]*#/ && index($0, "=") > 0 { print substr($0, 1, index($0, "=") - 1) }' "$merged" | sort -u)
}
