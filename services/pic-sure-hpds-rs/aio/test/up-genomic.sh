#!/usr/bin/env bash
# shellcheck disable=SC2015
# Dry-run test of up.sh's genomic mount and store directory override.
#
# Usage: aio/test/up-genomic.sh
#
# Runs up.sh --dry-run only, against a scratch DOCKER_CONFIG_DIR, container and network names
# that do not exist, and alpine:3.20 standing in for the image (up.sh only inspects it in a dry
# run). It creates no container, network or image and removes its scratch directory on exit.
set -euo pipefail

HERE="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
AIO="$(cd -P "$HERE/.." && pwd -P)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/aio-up-genomic.XXXXXX")"
WORK="$(cd -P "$WORK" && pwd -P)"
trap 'rm -rf "$WORK"' EXIT
failures=0

pass() { echo "PASS  $*"; }
check_failed() { echo "FAIL  $*"; failures=$((failures + 1)); }

docker image inspect alpine:3.20 >/dev/null 2>&1 || docker pull -q alpine:3.20 >/dev/null

export DOCKER_CONFIG_DIR="$WORK/cfg"
export HPDS_RS_IMAGE=alpine:3.20
export PICSURE_NETWORK=up-genomic-absent-picsure-t
export HPDS_NETWORK=up-genomic-absent-hpds-t
export HPDS_RS_CONTAINER=up-genomic-absent-hpds-rs-t
unset HPDS_RS_STORE_DIR HPDS_RS_GENOMIC_DIR

mkdir -p "$DOCKER_CONFIG_DIR/hpds" "$DOCKER_CONFIG_DIR/hpds-rs" "$WORK/v5-store" "$WORK/genomic" \
  "$DOCKER_CONFIG_DIR/hpds/all" "$DOCKER_CONFIG_DIR/hpds-rs/genomic" "$WORK/v5-store/genomic" "$WORK/empty-genomic"
touch "$DOCKER_CONFIG_DIR/hpds-rs/pheno.index" "$WORK/v5-store/pheno.index" "$WORK/genomic/geno.index" \
  "$DOCKER_CONFIG_DIR/hpds/all/geno.index" "$DOCKER_CONFIG_DIR/hpds-rs/genomic/geno.index" "$WORK/v5-store/genomic/geno.index"
printf 'ID_BATCH_SIZE=2000\n' > "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env"
printf 'ID_BATCH_SIZE=2000\nhpds.genomicProcessor.impl=localPatientDistributed\n' > "$WORK/v5-store/hpds-rs.env"

# Runs up.sh --dry-run with the given environment assignments and prints its output and exit code.
dry_run() {
  local status=0
  env "$@" "$AIO/up.sh" --dry-run > "$WORK/out" 2>&1 || status=$?
  echo "$status"
}

# Passes when the last dry run exited with $2 and its output holds the fixed string $3. $1 names the check.
expect_run() {
  local name="$1" want_status="$2" want_text="$3" status="$4"
  if [[ "$status" == "$want_status" ]] && grep -qF -- "$want_text" "$WORK/out"; then
    pass "$name"
  else
    check_failed "$name (exit $status, wanted $want_status and '$want_text')"
    sed 's/^/      /' "$WORK/out"
  fi
}

status="$(dry_run)"
expect_run "default mounts the hpds-rs store" 0 "$DOCKER_CONFIG_DIR/hpds-rs:/opt/local/hpds:ro" "$status"
grep -qF /opt/local/genomic "$WORK/out" && check_failed "no genomic mount without HPDS_RS_GENOMIC_DIR" || pass "no genomic mount without HPDS_RS_GENOMIC_DIR"

status="$(dry_run HPDS_RS_STORE_DIR="$WORK/v5-store")"
expect_run "store override mounts that store" 0 "$WORK/v5-store:/opt/local/hpds:ro" "$status"
expect_run "store override reads its own env file" 0 "--env-file $WORK/v5-store/hpds-rs.env" "$status"

status="$(dry_run HPDS_RS_STORE_DIR="$WORK/v5-store" HPDS_RS_GENOMIC_DIR="$WORK/genomic")"
expect_run "genomic directory mounted read-only" 0 "$WORK/genomic:/opt/local/genomic:ro" "$status"
expect_run "genomic data directory set to the mount" 0 "HPDS_GENOMIC_DATA_DIRECTORY=/opt/local/genomic/" "$status"

status="$(dry_run HPDS_RS_STORE_DIR="$WORK/v5-store" HPDS_RS_GENOMIC_DIR="$DOCKER_CONFIG_DIR/hpds/all")"
expect_run "genomic directory inside the Java store refused" 1 "lies in the Java store directory" "$status"

status="$(dry_run HPDS_RS_STORE_DIR="$WORK/v5-store" HPDS_RS_GENOMIC_DIR="$WORK/v5-store/genomic")"
expect_run "genomic directory inside the phenotype store refused" 1 "lies in the phenotype store" "$status"

status="$(dry_run HPDS_RS_STORE_DIR="$WORK/v5-store" HPDS_RS_GENOMIC_DIR="$WORK/empty-genomic")"
expect_run "genomic directory without geno.index refused" 1 "geno.index is missing" "$status"

status="$(dry_run HPDS_RS_GENOMIC_DIR="$WORK/genomic")"
expect_run "env without the genomic layout refused" 1 "sets neither hpds.genomicProcessor.impl" "$status"

printf 'ID_BATCH_SIZE=2000\nHPDS_GENOMIC_PROCESSOR_IMPL=localPatientDistributed\n' > "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env"
status="$(dry_run HPDS_RS_GENOMIC_DIR="$WORK/genomic")"
expect_run "Java's HPDS_GENOMIC_PROCESSOR_IMPL spelling refused" 1 "which hpds-rs does not read" "$status"

rm "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env"
status="$(dry_run HPDS_RS_GENOMIC_DIR="$WORK/genomic")"
expect_run "genomic directory without an env file refused" 1 "does not exist. With HPDS_RS_GENOMIC_DIR" "$status"

if [[ "$failures" -eq 0 ]]; then
  echo "up-genomic: all checks passed"
else
  echo "up-genomic: $failures checks failed"
  exit 1
fi
