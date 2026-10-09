#!/usr/bin/env bash
# Builds the images the hpds-rs all-in-one workflow runs.
#
# Usage: aio/build-images.sh [--dry-run] [--java-etl]
#
#   hms-dbmi/pic-sure-hpds-rs:LATEST       the service
#   hms-dbmi/pic-sure-hpds-rs-etl:LATEST   the Rust ETL (load, migrate)
#
# With --java-etl, also builds hms-dbmi/pic-sure-hpds-etl:migrate from the Java sources,
# but only when no local Java ETL image already holds ExportForMigration. The all-in-one's
# own hms-dbmi/pic-sure-hpds-etl:LATEST is never retagged. The Java build needs the ETL
# jars under services/pic-sure-hpds/docker/pic-sure-hpds-etl/; the script says what to run
# when they are missing.
set -euo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"
}

parse_args "$@"
want_java_etl=0
for arg in ${ARGS[@]+"${ARGS[@]}"}; do
  case "$arg" in
    --java-etl) want_java_etl=1 ;;
    *) fail "unknown argument $arg" ;;
  esac
done
require_docker

act docker build -f "$RS_ROOT/docker/hpds-rs.Dockerfile" -t "$HPDS_RS_IMAGE" "$RS_ROOT"
act docker build -f "$RS_ROOT/docker/hpds-rs-etl.Dockerfile" -t "$RUST_ETL_IMAGE" "$RS_ROOT"

if [[ "$want_java_etl" == "1" ]]; then
  existing="$(find_java_etl_image)"
  if [[ -n "$existing" ]]; then
    log "Java ETL image $existing already holds ExportForMigration; not building another"
  else
    java_root="$RS_ROOT/../pic-sure-hpds"
    jar="$java_root/docker/pic-sure-hpds-etl/ExportForMigration-jar-with-dependencies.jar"
    if [[ ! -f "$jar" && "$DRY_RUN" != "1" ]]; then
      fail "$jar is missing. From the pic-sure root run: JAVA_HOME=<jdk 25> mvn -pl services/pic-sure-hpds/etl -am install"
    fi
    act docker build -f "$java_root/docker/pic-sure-hpds-etl/Dockerfile" \
      -t hms-dbmi/pic-sure-hpds-etl:migrate "$java_root"
  fi
fi
