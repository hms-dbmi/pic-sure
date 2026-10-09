#!/usr/bin/env bash
# Builds the four-partition 1000 Genomes fixture with the harmonization Java ETL and runs the
# harmonization Java HPDS service on it.
# Usage: bench/harm-fixture.sh [build|start|stop|status|all]   (default: all)
# Env: JAVA_ETL_IMAGE, JAVA_HPDS_IMAGE, CONTAINER, HOST_PORT, DATA_DIR, SOURCE_DIR, REQUIRE_AUTH_FILTER
# REQUIRE_AUTH_FILTER=true runs the strict mode (default false, as the integration profile does).
# The service port is published on 127.0.0.1 only and the default container name is v2-hpds-harm.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
JAVA_ETL_IMAGE="${JAVA_ETL_IMAGE:-hms-dbmi/pic-sure-hpds-etl:harm}"
JAVA_HPDS_IMAGE="${JAVA_HPDS_IMAGE:-hms-dbmi/pic-sure-hpds:harm}"
CONTAINER="${CONTAINER:-v2-hpds-harm}"
HOST_PORT="${HOST_PORT:-18095}"
DATA_DIR="${DATA_DIR:-$HERE/data/harm-fixture}"
SOURCE_DIR="${SOURCE_DIR:-$REPO/services/pic-sure-hpds/service/src/test/resources/phenotypic}"
REQUIRE_AUTH_FILTER="${REQUIRE_AUTH_FILTER:-false}"

require_image() {
  local image="$1" target="$2"
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "image $image not found. Build the Java artifacts and the image first:" >&2
    echo "  mvn -pl services/pic-sure-hpds/service,services/pic-sure-hpds/etl -am package" >&2
    echo "  docker build -t $image -f services/pic-sure-hpds/docker/$target/Dockerfile services/pic-sure-hpds" >&2
    echo "or point ${3} at an existing image." >&2
    return 1
  fi
}

build() {
  require_image "$JAVA_ETL_IMAGE" pic-sure-hpds-etl JAVA_ETL_IMAGE || return 1
  mkdir -p "$DATA_DIR"
  chmod 777 "$DATA_DIR"
  if [[ -s "$DATA_DIR/partition1/columnMeta.csv" && -s "$DATA_DIR/partition4/allObservationsStore.javabin" ]]; then
    echo "fixture already built in $DATA_DIR"
    return 0
  fi
  [[ -s "$DATA_DIR/encryption_key" ]] || openssl rand -hex 16 | tr -d '\n' > "$DATA_DIR/encryption_key"
  chmod 666 "$DATA_DIR/encryption_key"
  cp "$SOURCE_DIR"/partition*-allConcepts.csv "$DATA_DIR/"
  chmod 666 "$DATA_DIR"/partition*-allConcepts.csv
  docker run --rm -e LOADER_NAME=CSVLoader -e HEAPSIZE=2048 -v "$DATA_DIR:/opt/local/hpds" "$JAVA_ETL_IMAGE"
  rm -f "$DATA_DIR"/partition*-allConcepts.csv
}

running() {
  [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)" == "true" ]]
}

matches_configuration() {
  local image mount published
  image="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER" 2>/dev/null || true)"
  mount="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/opt/local/hpds"}}{{.Source}}{{end}}{{end}}' "$CONTAINER" 2>/dev/null || true)"
  published="$(docker port "$CONTAINER" 8080/tcp 2>/dev/null || true)"
  [[ "$image" == "$JAVA_HPDS_IMAGE" \
    && "$mount" == "$(cd "$DATA_DIR" && pwd -P)" \
    && "$published" == *"127.0.0.1:$HOST_PORT"* ]]
}

start() {
  require_image "$JAVA_HPDS_IMAGE" pic-sure-hpds JAVA_HPDS_IMAGE || return 1
  if running && matches_configuration; then
    echo "$CONTAINER already running"
  else
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker run -d --name "$CONTAINER" -p "127.0.0.1:$HOST_PORT:8080" \
      -v "$DATA_DIR:/opt/local/hpds" \
      -e SMALL_JOB_LIMIT=100 -e SMALL_TASK_THREADS=4 -e LARGE_TASK_THREADS=2 \
      -e PICSURE_ACTUATOR_EXPOSURE=health -e hpds.requireAuthorizationFilter="$REQUIRE_AUTH_FILTER" \
      -e ID_BATCH_SIZE=1000 -e CACHE_SIZE=1500 -e JAVA_OPTS=-Xmx4g \
      "$JAVA_HPDS_IMAGE" >/dev/null
  fi
  for _ in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:$HOST_PORT/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      echo "$CONTAINER healthy on http://127.0.0.1:$HOST_PORT/PIC-SURE"
      return 0
    fi
    sleep 2
  done
  echo "$CONTAINER did not become healthy" >&2
  docker logs --tail 40 "$CONTAINER" >&2 || true
  return 1
}

stop() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}

case "${1:-all}" in
  build) build ;;
  start) start ;;
  stop) stop ;;
  status) running && echo running || echo stopped ;;
  all) build; start ;;
  *) echo "usage: $0 [build|start|stop|status|all]" >&2; exit 2 ;;
esac
