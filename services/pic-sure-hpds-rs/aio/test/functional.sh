#!/usr/bin/env bash
# shellcheck disable=SC2015
# Functional test of the aio scripts against throwaway containers and networks.
#
# Usage: aio/test/functional.sh
#
# Needs the images hms-dbmi/pic-sure-hpds-rs:LATEST, hms-dbmi/pic-sure-hpds-rs-etl:LATEST and a
# Java ETL image holding ExportForMigration, plus alpine:3.20. It never touches the real
# all-in-one: every container and network carries a -t name, DOCKER_CONFIG_DIR points at a
# scratch directory, and a fake query service (an alpine container with a bind mount, a port,
# a label and two networks) stands in for the real one. Everything it creates is removed on
# exit.
set -euo pipefail

HERE="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
AIO="$(cd -P "$HERE/.." && pwd -P)"
RS_ROOT="$(cd -P "$AIO/.." && pwd -P)"
FIXTURE="$RS_ROOT/bench/data/1000genomes"
SOURCE_CSV="$RS_ROOT/../pic-sure-hpds/service/src/test/resources/phenotypic/allConcepts.csv"

export PICSURE_NETWORK=picsure-t
export HPDS_NETWORK=hpds-t
export QS_CONTAINER=qs-t
export HPDS_RS_CONTAINER=hpds-rs-t
export HPDS_JAVA_CONTAINER=hpds-java-t
export HEALTH_TIMEOUT=60
export HPDS_RS_SWAP_GRACE_SECONDS=1

WORK="$(mktemp -d "${TMPDIR:-/tmp}/aio-functional.XXXXXX")"
export DOCKER_CONFIG_DIR="$WORK/cfg"
failures=0

pass() { echo "PASS  $*"; }
check_failed() { echo "FAIL  $*"; failures=$((failures + 1)); }

expect_equal() {
  if [[ "$2" == "$3" ]]; then pass "$1"; else check_failed "$1 (expected '$3', got '$2')"; fi
}

cleanup() {
  docker rm -f qs-t qs-t-switch-new qs-t-switch-old hpds-rs-t hpds-rs-t-new hpds-rs-t-old hpds-java-t probe-t >/dev/null 2>&1 || true
  docker network rm picsure-t hpds-t >/dev/null 2>&1 || true
  docker rmi aio-test-qs-t >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fingerprint() {
  docker container inspect "$1" | jq -S '.[0] | {
    Name,
    Image,
    Config: {Image: .Config.Image, Labels: .Config.Labels, Cmd: .Config.Cmd, Entrypoint: .Config.Entrypoint,
             User: .Config.User, WorkingDir: .Config.WorkingDir, Healthcheck: .Config.Healthcheck},
    HostConfig: {RestartPolicy: .HostConfig.RestartPolicy, PortBindings: .HostConfig.PortBindings,
                 NetworkMode: .HostConfig.NetworkMode, LogConfig: .HostConfig.LogConfig},
    Mounts: ([.Mounts[] | {Type, Source, Destination, RW}] | sort_by(.Destination)),
    Networks: (.NetworkSettings.Networks | map_values((.Aliases // []) | map(select(length != 12))))
  }'
}

env_value_of() {
  sed -n "s/^$2=//p" "$1" | tail -n 1
}

env_of() {
  docker container inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1"
}

if docker network inspect picsure-t >/dev/null 2>&1 || docker container inspect qs-t >/dev/null 2>&1; then
  echo "refusing to run: leftover test network or container exists" >&2
  trap - EXIT
  rm -rf "$WORK"
  exit 1
fi

mkdir -p "$DOCKER_CONFIG_DIR"/{query,hpds,hpds_input,log} "$WORK/qs-data"
cp "$FIXTURE/encryption_key" "$DOCKER_CONFIG_DIR/hpds/encryption_key"
cp "$SOURCE_CSV" "$DOCKER_CONFIG_DIR/hpds_input/allConcepts.csv"
printf 'ID_BATCH_SIZE=1500\nSMALL_TASK_THREADS=3\nLARGE_TASK_THREADS=2\nhpds.requireAuthorizationFilter=false\n' > "$DOCKER_CONFIG_DIR/hpds/hpds.env"
cat > "$DOCKER_CONFIG_DIR/query/query.env" <<'ENV'
# fake query service env
HPDS_AUTH_URL=http://hpds-java-t:8080/PIC-SURE
HPDS_OPEN_URL=http://hpds-java-t:8080/PIC-SURE
HPDS_AUTH_TOKEN=

CONSENT_BASED_AUTHORIZATION_ENABLED=false
QS_OK=1
SECRET_ONE=abc=def
ENV
cp -p "$DOCKER_CONFIG_DIR/query/query.env" "$WORK/query.env.original"

docker build -q -t aio-test-qs-t - >/dev/null <<'DOCKERFILE'
FROM alpine:3.20
HEALTHCHECK --interval=1s --retries=1 CMD test "$QS_OK" = 1
CMD ["sleep", "3600"]
DOCKERFILE
docker network create picsure-t >/dev/null
docker network create --internal hpds-t >/dev/null

docker run -d --name qs-t --restart unless-stopped --network picsure-t --network-alias qs-alias-t \
  --env-file "$DOCKER_CONFIG_DIR/query/query.env" -e EXTRA_FROM_RUN=keep \
  -v "$WORK/qs-data:/data" -v "$WORK/qs-data:/data-ro:ro" -p 127.0.0.1:28931:9999 \
  --label com.example.test=qs aio-test-qs-t >/dev/null
docker network connect --alias qs-hpds-alias-t hpds-t qs-t

echo "--- status before anything"
expect_equal "status: engine java" "$("$AIO/status.sh" | sed -n 's/^  engine in use: //p')" java

echo "--- load csv"
"$AIO/load.sh" csv
[[ -f "$DOCKER_CONFIG_DIR/hpds-rs/pheno.index" ]] && pass "csv load wrote pheno.index" || check_failed "csv load wrote pheno.index"
expect_equal "csv load key mode" "$(stat -f '%Lp' "$DOCKER_CONFIG_DIR/hpds-rs/encryption_key")" 600
[[ ! -e "$DOCKER_CONFIG_DIR/hpds-rs/.csv-next" ]] && pass "csv staging removed" || check_failed "csv staging removed"

echo "--- switch rust refuses while hpds-rs is absent"
if "$AIO/switch.sh" rust >/dev/null 2>&1; then check_failed "switch rust refused"; else pass "switch rust refused"; fi
cmp -s "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env" && pass "query.env untouched by the refusal" || check_failed "query.env untouched by the refusal"
[[ ! -e "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" ]] && pass "no backup written by the refusal" || check_failed "no backup written by the refusal"

echo "--- up"
"$AIO/up.sh"
expect_equal "hpds-rs health" "$(docker container inspect -f '{{.State.Health.Status}}' hpds-rs-t)" healthy
grep -q '^ID_BATCH_SIZE=1500$' "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env" && pass "env template took ID_BATCH_SIZE from hpds.env" || check_failed "env template took ID_BATCH_SIZE"
for pair in SMALL_TASK_THREADS=3 LARGE_TASK_THREADS=2 HPDS_REQUIRE_AUTHORIZATION_FILTER=false; do
  grep -q "^$pair\$" "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env" && pass "hpds-rs.env has $pair" || check_failed "hpds-rs.env has $pair"
done
expect_equal "running container got the thread setting" "$(env_of hpds-rs-t | sed -n 's/^SMALL_TASK_THREADS=//p')" 3
expect_equal "hpds-rs networks" "$(docker container inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' hpds-rs-t | tr ' ' '\n' | sort | xargs)" "hpds-t picsure-t"
docker run -d --name probe-t --network picsure-t alpine:3.20 sleep 600 >/dev/null
reachable() { docker exec probe-t wget -q -T 5 -O - "http://$1:8080/actuator/health" 2>&1; }
expect_equal "actuator health over the network" "$(reachable hpds-rs-t | tr -d ' ')" '{"status":"UP"}'
writable="$(docker exec hpds-rs-t sh -c 'touch /opt/local/hpds/x' 2>&1 || true)"
[[ -z "$writable" ]] && check_failed "store mount is read-only" || pass "store mount is read-only"

echo "--- switch rust"
before_fp="$(fingerprint qs-t)"
before_id="$(docker container inspect -f '{{.Id}}' qs-t)"
"$AIO/switch.sh" rust
after_fp="$(fingerprint qs-t)"
after_id="$(docker container inspect -f '{{.Id}}' qs-t)"
[[ "$before_id" != "$after_id" ]] && pass "qs container was recreated" || check_failed "qs container was recreated"
expect_equal "inspect identical except env and ids" "$after_fp" "$before_fp"
expect_equal "running HPDS_AUTH_URL" "$(env_of qs-t | sed -n 's/^HPDS_AUTH_URL=//p')" "http://hpds-rs-t:8080/PIC-SURE"
expect_equal "running HPDS_OPEN_URL" "$(env_of qs-t | sed -n 's/^HPDS_OPEN_URL=//p')" "http://hpds-rs-t:8080/PIC-SURE"
expect_equal "docker-run-only env kept" "$(env_of qs-t | sed -n 's/^EXTRA_FROM_RUN=//p')" keep
expect_equal "value containing = kept" "$(env_of qs-t | sed -n 's/^SECRET_ONE=//p')" "abc=def"
expect_equal "image env not duplicated" "$(env_of qs-t | grep -c '^PATH=')" 1
expect_equal "query.env changed on exactly the two URL lines" "$(diff "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env" | grep -c '^[<>]')" 4
cmp -s "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" && pass "backup equals the original" || check_failed "backup equals the original"
expect_equal "status engine" "$("$AIO/status.sh" | sed -n 's/^  engine in use: //p')" rust
expect_equal "qs name resolves on picsure-t" "$(docker exec probe-t sh -c 'getent hosts qs-t >/dev/null && echo ok')" ok
expect_equal "qs alias resolves on picsure-t" "$(docker exec probe-t sh -c 'getent hosts qs-alias-t >/dev/null && echo ok')" ok
expect_equal "no leftover switch containers" "$(docker ps -a --format '{{.Names}}' | grep -c '^qs-t-switch' || true)" 0

echo "--- switch rust again keeps the backup"
backup_sum="$(cksum < "$DOCKER_CONFIG_DIR/query/query.env.pre-rust")"
"$AIO/switch.sh" rust
expect_equal "backup unchanged" "$(cksum < "$DOCKER_CONFIG_DIR/query/query.env.pre-rust")" "$backup_sum"
expect_equal "fingerprint still identical" "$(fingerprint qs-t)" "$before_fp"

echo "--- switch java"
"$AIO/switch.sh" java
expect_equal "running HPDS_AUTH_URL back" "$(env_of qs-t | sed -n 's/^HPDS_AUTH_URL=//p')" "http://hpds-java-t:8080/PIC-SURE"
cmp -s "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env" && pass "query.env restored byte for byte" || check_failed "query.env restored byte for byte"
expect_equal "fingerprint after java" "$(fingerprint qs-t)" "$before_fp"
[[ ! -e "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" && -e "$DOCKER_CONFIG_DIR/query/query.env.pre-rust.used" ]] && pass "backup retired" || check_failed "backup retired"

echo "--- a recreate docker rejects leaves the old container running"
keep_id="$(docker container inspect -f '{{.Id}}' qs-t)"
reset_query_env() {
  cp "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env"
  rm -f "$DOCKER_CONFIG_DIR/query/query.env.pre-rust"
}
printf 'BAD KEY=1\n' >> "$DOCKER_CONFIG_DIR/query/query.env"
rejected_sum="$(cksum < "$DOCKER_CONFIG_DIR/query/query.env")"
if "$AIO/switch.sh" rust >/dev/null 2>&1; then check_failed "rejected create fails the switch"; else pass "rejected create fails the switch"; fi
expect_equal "old container untouched" "$(docker container inspect -f '{{.Id}} {{.State.Running}}' qs-t)" "$keep_id true"
expect_equal "no leftover switch containers after rejection" "$(docker ps -a --format '{{.Names}}' | grep -c '^qs-t-switch' || true)" 0
expect_equal "query.env untouched after the rejected create" "$(cksum < "$DOCKER_CONFIG_DIR/query/query.env")" "$rejected_sum"
[[ ! -e "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" ]] && pass "no backup left by the rejected create" || check_failed "no backup left by the rejected create"
reset_query_env

echo "--- an unhealthy replacement rolls back"
sed -i.bak 's/^QS_OK=1$/QS_OK=0/' "$DOCKER_CONFIG_DIR/query/query.env"
rm -f "$DOCKER_CONFIG_DIR/query/query.env.bak"
unhealthy_sum="$(cksum < "$DOCKER_CONFIG_DIR/query/query.env")"
if HEALTH_TIMEOUT=15 "$AIO/switch.sh" rust >/dev/null 2>&1; then check_failed "unhealthy replacement fails the switch"; else pass "unhealthy replacement fails the switch"; fi
expect_equal "original container is back" "$(docker container inspect -f '{{.Id}} {{.State.Running}}' qs-t)" "$keep_id true"
expect_equal "original env unchanged" "$(env_of qs-t | sed -n 's/^HPDS_AUTH_URL=//p')" "http://hpds-java-t:8080/PIC-SURE"
expect_equal "no leftover switch containers after rollback" "$(docker ps -a --format '{{.Names}}' | grep -c '^qs-t-switch' || true)" 0
expect_equal "query.env matches the running container after rollback" "$(cksum < "$DOCKER_CONFIG_DIR/query/query.env")" "$unhealthy_sum"
expect_equal "query.env URLs still java after rollback" "$(env_value_of "$DOCKER_CONFIG_DIR/query/query.env" HPDS_AUTH_URL)" "http://hpds-java-t:8080/PIC-SURE"
[[ ! -e "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" ]] && pass "no backup left by the rollback" || check_failed "no backup left by the rollback"
reset_query_env

echo "--- migrate while running recreates hpds-rs"
cp "$FIXTURE/columnMeta.javabin" "$FIXTURE/allObservationsStore.javabin" "$DOCKER_CONFIG_DIR/hpds/"
rs_before="$(docker container inspect -f '{{.Id}}' hpds-rs-t)"
"$AIO/load.sh" migrate
[[ "$rs_before" != "$(docker container inspect -f '{{.Id}}' hpds-rs-t)" ]] && pass "hpds-rs recreated after migrate" || check_failed "hpds-rs recreated after migrate"
expect_equal "hpds-rs healthy after migrate" "$(docker container inspect -f '{{.State.Health.Status}}' hpds-rs-t)" healthy
expect_equal "migrate key mode" "$(stat -f '%Lp' "$DOCKER_CONFIG_DIR/hpds-rs/encryption_key")" 600
"$AIO/status.sh"

echo "--- up keeps the old hpds-rs when the new one is unhealthy"
old_rs_id="$(docker container inspect -f '{{.Id}}' hpds-rs-t)"
cp -p "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env" "$WORK/hpds-rs.env.good"
sed -i.bak 's/^ID_BATCH_SIZE=.*/ID_BATCH_SIZE=not-a-number/' "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env"
rm -f "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env.bak"
if HEALTH_TIMEOUT=30 "$AIO/up.sh" >/dev/null 2>&1; then check_failed "up fails when the new container is unhealthy"; else pass "up fails when the new container is unhealthy"; fi
expect_equal "old hpds-rs still running" "$(docker container inspect -f '{{.Id}} {{.State.Running}}' hpds-rs-t)" "$old_rs_id true"
expect_equal "old hpds-rs still healthy" "$(docker container inspect -f '{{.State.Health.Status}}' hpds-rs-t)" healthy
expect_equal "no leftover hpds-rs temporary containers" "$(docker ps -a --format '{{.Names}}' | grep -c '^hpds-rs-t-' || true)" 0
cp -p "$WORK/hpds-rs.env.good" "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env"

echo "--- a replaced hpds-rs keeps serving its old address during the grace period"
ip_on_picsure() {
  docker container inspect -f '{{(index .NetworkSettings.Networks "picsure-t").IPAddress}}' "$1"
}
probe() {
  docker run --rm --network picsure-t alpine:3.20 sh -c "$1" 2>&1
}
old_rs_ip="$(ip_on_picsure hpds-rs-t)"
HPDS_RS_SWAP_GRACE_SECONDS=30 "$AIO/up.sh" > "$WORK/up-grace.log" 2>&1 &
up_pid=$!
waited=0
until [[ "$(docker container inspect -f '{{.Name}}' hpds-rs-t-old 2>/dev/null || true)" == "/hpds-rs-t-old" ]] \
  && [[ "$(docker container inspect -f '{{.Id}}' hpds-rs-t 2>/dev/null || true)" != "$old_rs_id" ]]; do
  [[ "$waited" -lt 90 ]] || break
  sleep 1
  waited=$((waited + 1))
done
expect_equal "old container renamed and still running" "$(docker container inspect -f '{{.Id}} {{.State.Running}}' hpds-rs-t-old 2>/dev/null || echo absent)" "$old_rs_id true"
expect_equal "old container keeps its address" "$(ip_on_picsure hpds-rs-t-old 2>/dev/null || echo absent)" "$old_rs_ip"
expect_equal "old address still answers health" "$(probe "wget -q -T 5 -O - http://$old_rs_ip:8080/actuator/health" | tr -d ' ')" '{"status":"UP"}'
new_rs_ip="$(ip_on_picsure hpds-rs-t)"
[[ -n "$new_rs_ip" && "$new_rs_ip" != "$old_rs_ip" ]] && pass "replacement has its own address" || check_failed "replacement has its own address"
expect_equal "the name resolves to the replacement" "$(probe 'getent hosts hpds-rs-t' | awk '{print $1}')" "$new_rs_ip"
if wait "$up_pid"; then pass "up finished after the grace period"; else check_failed "up finished after the grace period"; cat "$WORK/up-grace.log"; fi
expect_equal "old container removed after the grace period" "$(docker container inspect hpds-rs-t-old >/dev/null 2>&1 && echo present || echo gone)" gone
[[ "$old_rs_id" != "$(docker container inspect -f '{{.Id}}' hpds-rs-t)" ]] && pass "up swapped in a healthy replacement" || check_failed "up swapped in a healthy replacement"
expect_equal "replacement healthy" "$(docker container inspect -f '{{.State.Health.Status}}' hpds-rs-t)" healthy
expect_equal "replacement restarts always" "$(docker container inspect -f '{{.HostConfig.RestartPolicy.Name}}' hpds-rs-t)" always
expect_equal "no leftover hpds-rs temporary containers after the swap" "$(docker ps -a --format '{{.Names}}' | grep -c '^hpds-rs-t-' || true)" 0

echo "--- down while qs points at rust"
"$AIO/switch.sh" rust
"$AIO/down.sh"
expect_equal "hpds-rs removed" "$(docker container inspect hpds-rs-t >/dev/null 2>&1 && echo present || echo gone)" gone
expect_equal "qs back on java" "$(env_of qs-t | sed -n 's/^HPDS_AUTH_URL=//p')" "http://hpds-java-t:8080/PIC-SURE"
[[ -f "$DOCKER_CONFIG_DIR/hpds-rs/pheno.index" ]] && pass "down kept the store" || check_failed "down kept the store"
"$AIO/down.sh" >/dev/null && pass "down is idempotent" || check_failed "down is idempotent"

echo "--- down with no query service container restores the file"
"$AIO/up.sh"
"$AIO/switch.sh" rust
mv "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" "$DOCKER_CONFIG_DIR/query/query.env.java-backup"
docker rm -f qs-t >/dev/null
"$AIO/down.sh"
expect_equal "hpds-rs removed by the second down" "$(docker container inspect hpds-rs-t >/dev/null 2>&1 && echo present || echo gone)" gone
cmp -s "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env" && pass "query.env restored from an earlier-named backup with no query service" || check_failed "query.env restored from an earlier-named backup with no query service"
[[ -e "$DOCKER_CONFIG_DIR/query/query.env.java-backup.used" ]] && pass "earlier-named backup retired" || check_failed "earlier-named backup retired"

echo "--- unset DOCKER_CONFIG_DIR"
unset_output="$(env -u DOCKER_CONFIG_DIR "$AIO/status.sh" 2>&1 || true)"
[[ "$unset_output" == *"DOCKER_CONFIG_DIR is not set"* ]] && pass "clear error when unset" || check_failed "clear error when unset"

echo
if [[ "$failures" -eq 0 ]]; then echo "all checks passed"; else echo "$failures check(s) failed"; exit 1; fi
