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
#
# Env:
#   HPDS_RS_IMAGE, RUST_ETL_IMAGE, JAVA_ETL_IMAGE   the images to test, as aio/lib.sh reads them
#   FUNCTIONAL_PREFIX  prefix of every container, network and test image name (empty), for
#                      example v4- to keep a run apart from other test runs; lowercase letters,
#                      digits and hyphens only
#   FIXTURE            javabin store the migrate step copies (bench/data/1000genomes)
#   SOURCE_CSV         allConcepts.csv the csv load step reads (the phenotype test resource)
#   PARTITIONED_FIXTURE  consent-partitioned javabin store the partitioned migrate step reads
#                      (bench/data/harm-fixture, built by bench/harm-fixture.sh build): one
#                      encryption_key and one subdirectory per partition
set -euo pipefail

HERE="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
AIO="$(cd -P "$HERE/.." && pwd -P)"
RS_ROOT="$(cd -P "$AIO/.." && pwd -P)"
FIXTURE="${FIXTURE:-$RS_ROOT/bench/data/1000genomes}"
SOURCE_CSV="${SOURCE_CSV:-$RS_ROOT/../pic-sure-hpds/service/src/test/resources/phenotypic/allConcepts.csv}"
PARTITIONED_FIXTURE="${PARTITIONED_FIXTURE:-$RS_ROOT/bench/data/harm-fixture}"
PREFIX="${FUNCTIONAL_PREFIX:-}"
if [[ ! "$PREFIX" =~ ^[a-z0-9-]*$ ]]; then
  echo "FUNCTIONAL_PREFIX may hold only lowercase letters, digits and hyphens" >&2
  exit 2
fi
for required in "$FIXTURE/encryption_key" "$FIXTURE/columnMeta.javabin" "$FIXTURE/allObservationsStore.javabin" "$SOURCE_CSV" \
  "$PARTITIONED_FIXTURE/encryption_key" "$PARTITIONED_FIXTURE/partition1/allObservationsStore.javabin"; do
  [[ -f "$required" ]] || { echo "missing $required; set FIXTURE, SOURCE_CSV or PARTITIONED_FIXTURE" >&2; exit 2; }
done
PICSURE_T="${PREFIX}picsure-t"
HPDS_T="${PREFIX}hpds-t"
QS_T="${PREFIX}qs-t"
RS_T="${PREFIX}hpds-rs-t"
JAVA_T="${PREFIX}hpds-java-t"
PROBE_T="${PREFIX}probe-t"
QS_IMAGE="${PREFIX}aio-test-qs-t"
QS_ALIAS="${PREFIX}qs-alias-t"
QS_HPDS_ALIAS="${PREFIX}qs-hpds-alias-t"
PART_T="${PREFIX}hpds-rs-part-t"

export PICSURE_NETWORK=${PICSURE_T}
export HPDS_NETWORK=${HPDS_T}
export QS_CONTAINER=${QS_T}
export HPDS_RS_CONTAINER=${RS_T}
export HPDS_JAVA_CONTAINER=${JAVA_T}
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
  docker rm -f ${QS_T} ${QS_T}-switch-new ${QS_T}-switch-old ${RS_T} ${RS_T}-new ${RS_T}-old ${JAVA_T} ${PROBE_T} ${PART_T} >/dev/null 2>&1 || true
  docker network rm ${PICSURE_T} ${HPDS_T} >/dev/null 2>&1 || true
  docker rmi ${QS_IMAGE} >/dev/null 2>&1 || true
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

if docker network inspect ${PICSURE_T} >/dev/null 2>&1 || docker container inspect ${QS_T} >/dev/null 2>&1 \
  || docker container inspect ${PART_T} >/dev/null 2>&1; then
  echo "refusing to run: leftover test network or container exists" >&2
  trap - EXIT
  rm -rf "$WORK"
  exit 1
fi

mkdir -p "$DOCKER_CONFIG_DIR"/{query,hpds,hpds_input,log} "$WORK/qs-data"
cp "$FIXTURE/encryption_key" "$DOCKER_CONFIG_DIR/hpds/encryption_key"
cp "$SOURCE_CSV" "$DOCKER_CONFIG_DIR/hpds_input/allConcepts.csv"
printf 'ID_BATCH_SIZE=1500\nSMALL_TASK_THREADS=3\nLARGE_TASK_THREADS=2\nhpds.requireAuthorizationFilter=false\n' > "$DOCKER_CONFIG_DIR/hpds/hpds.env"
cat > "$DOCKER_CONFIG_DIR/query/query.env" <<ENV
# fake query service env
HPDS_AUTH_URL=http://${JAVA_T}:8080/PIC-SURE
HPDS_OPEN_URL=http://${JAVA_T}:8080/PIC-SURE
HPDS_AUTH_TOKEN=

CONSENT_BASED_AUTHORIZATION_ENABLED=false
QS_OK=1
SECRET_ONE=abc=def
ENV
cp -p "$DOCKER_CONFIG_DIR/query/query.env" "$WORK/query.env.original"

docker build -q -t ${QS_IMAGE} - >/dev/null <<'DOCKERFILE'
FROM alpine:3.20
HEALTHCHECK --interval=1s --retries=1 CMD test "$QS_OK" = 1
CMD ["sleep", "3600"]
DOCKERFILE
docker network create ${PICSURE_T} >/dev/null
docker network create --internal ${HPDS_T} >/dev/null

docker run -d --name ${QS_T} --restart unless-stopped --network ${PICSURE_T} --network-alias ${QS_ALIAS} \
  --env-file "$DOCKER_CONFIG_DIR/query/query.env" -e EXTRA_FROM_RUN=keep \
  -v "$WORK/qs-data:/data" -v "$WORK/qs-data:/data-ro:ro" -p 127.0.0.1:28931:9999 \
  --label com.example.test=qs ${QS_IMAGE} >/dev/null
docker network connect --alias ${QS_HPDS_ALIAS} ${HPDS_T} ${QS_T}

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
expect_equal "hpds-rs health" "$(docker container inspect -f '{{.State.Health.Status}}' ${RS_T})" healthy
grep -q '^ID_BATCH_SIZE=1500$' "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env" && pass "env template took ID_BATCH_SIZE from hpds.env" || check_failed "env template took ID_BATCH_SIZE"
for pair in SMALL_TASK_THREADS=3 LARGE_TASK_THREADS=2 HPDS_REQUIRE_AUTHORIZATION_FILTER=false; do
  grep -q "^$pair\$" "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env" && pass "hpds-rs.env has $pair" || check_failed "hpds-rs.env has $pair"
done
expect_equal "running container got the thread setting" "$(env_of ${RS_T} | sed -n 's/^SMALL_TASK_THREADS=//p')" 3
expect_equal "hpds-rs networks" "$(docker container inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' ${RS_T} | tr ' ' '\n' | sort | xargs)" "${HPDS_T} ${PICSURE_T}"
docker run -d --name ${PROBE_T} --network ${PICSURE_T} alpine:3.20 sleep 600 >/dev/null
reachable() { docker exec ${PROBE_T} wget -q -T 5 -O - "http://$1:8080/actuator/health" 2>&1; }
expect_equal "actuator health over the network" "$(reachable ${RS_T} | jq -r .status 2>/dev/null)" UP
writable="$(docker exec ${RS_T} sh -c 'touch /opt/local/hpds/x' 2>&1 || true)"
[[ -z "$writable" ]] && check_failed "store mount is read-only" || pass "store mount is read-only"

echo "--- switch rust"
before_fp="$(fingerprint ${QS_T})"
before_id="$(docker container inspect -f '{{.Id}}' ${QS_T})"
"$AIO/switch.sh" rust
after_fp="$(fingerprint ${QS_T})"
after_id="$(docker container inspect -f '{{.Id}}' ${QS_T})"
[[ "$before_id" != "$after_id" ]] && pass "qs container was recreated" || check_failed "qs container was recreated"
expect_equal "inspect identical except env and ids" "$after_fp" "$before_fp"
expect_equal "running HPDS_AUTH_URL" "$(env_of ${QS_T} | sed -n 's/^HPDS_AUTH_URL=//p')" "http://${RS_T}:8080/PIC-SURE"
expect_equal "running HPDS_OPEN_URL" "$(env_of ${QS_T} | sed -n 's/^HPDS_OPEN_URL=//p')" "http://${RS_T}:8080/PIC-SURE"
expect_equal "docker-run-only env kept" "$(env_of ${QS_T} | sed -n 's/^EXTRA_FROM_RUN=//p')" keep
expect_equal "value containing = kept" "$(env_of ${QS_T} | sed -n 's/^SECRET_ONE=//p')" "abc=def"
expect_equal "image env not duplicated" "$(env_of ${QS_T} | grep -c '^PATH=')" 1
expect_equal "query.env changed on exactly the two URL lines" "$(diff "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env" | grep -c '^[<>]')" 4
cmp -s "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" && pass "backup equals the original" || check_failed "backup equals the original"
expect_equal "status engine" "$("$AIO/status.sh" | sed -n 's/^  engine in use: //p')" rust
expect_equal "qs name resolves on ${PICSURE_T}" "$(docker exec ${PROBE_T} sh -c "getent hosts ${QS_T} >/dev/null && echo ok")" ok
expect_equal "qs alias resolves on ${PICSURE_T}" "$(docker exec ${PROBE_T} sh -c "getent hosts ${QS_ALIAS} >/dev/null && echo ok")" ok
expect_equal "no leftover switch containers" "$(docker ps -a --format '{{.Names}}' | grep -c "^${QS_T}-switch" || true)" 0

echo "--- switch rust again keeps the backup"
backup_sum="$(cksum < "$DOCKER_CONFIG_DIR/query/query.env.pre-rust")"
"$AIO/switch.sh" rust
expect_equal "backup unchanged" "$(cksum < "$DOCKER_CONFIG_DIR/query/query.env.pre-rust")" "$backup_sum"
expect_equal "fingerprint still identical" "$(fingerprint ${QS_T})" "$before_fp"

echo "--- switch java"
"$AIO/switch.sh" java
expect_equal "running HPDS_AUTH_URL back" "$(env_of ${QS_T} | sed -n 's/^HPDS_AUTH_URL=//p')" "http://${JAVA_T}:8080/PIC-SURE"
cmp -s "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env" && pass "query.env restored byte for byte" || check_failed "query.env restored byte for byte"
expect_equal "fingerprint after java" "$(fingerprint ${QS_T})" "$before_fp"
[[ ! -e "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" && -e "$DOCKER_CONFIG_DIR/query/query.env.pre-rust.used" ]] && pass "backup retired" || check_failed "backup retired"

echo "--- a recreate docker rejects leaves the old container running"
keep_id="$(docker container inspect -f '{{.Id}}' ${QS_T})"
reset_query_env() {
  cp "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env"
  rm -f "$DOCKER_CONFIG_DIR/query/query.env.pre-rust"
}
printf 'BAD KEY=1\n' >> "$DOCKER_CONFIG_DIR/query/query.env"
rejected_sum="$(cksum < "$DOCKER_CONFIG_DIR/query/query.env")"
if "$AIO/switch.sh" rust >/dev/null 2>&1; then check_failed "rejected create fails the switch"; else pass "rejected create fails the switch"; fi
expect_equal "old container untouched" "$(docker container inspect -f '{{.Id}} {{.State.Running}}' ${QS_T})" "$keep_id true"
expect_equal "no leftover switch containers after rejection" "$(docker ps -a --format '{{.Names}}' | grep -c "^${QS_T}-switch" || true)" 0
expect_equal "query.env untouched after the rejected create" "$(cksum < "$DOCKER_CONFIG_DIR/query/query.env")" "$rejected_sum"
[[ ! -e "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" ]] && pass "no backup left by the rejected create" || check_failed "no backup left by the rejected create"
reset_query_env

echo "--- an unhealthy replacement rolls back"
sed -i.bak 's/^QS_OK=1$/QS_OK=0/' "$DOCKER_CONFIG_DIR/query/query.env"
rm -f "$DOCKER_CONFIG_DIR/query/query.env.bak"
unhealthy_sum="$(cksum < "$DOCKER_CONFIG_DIR/query/query.env")"
if HEALTH_TIMEOUT=15 "$AIO/switch.sh" rust >/dev/null 2>&1; then check_failed "unhealthy replacement fails the switch"; else pass "unhealthy replacement fails the switch"; fi
expect_equal "original container is back" "$(docker container inspect -f '{{.Id}} {{.State.Running}}' ${QS_T})" "$keep_id true"
expect_equal "original env unchanged" "$(env_of ${QS_T} | sed -n 's/^HPDS_AUTH_URL=//p')" "http://${JAVA_T}:8080/PIC-SURE"
expect_equal "no leftover switch containers after rollback" "$(docker ps -a --format '{{.Names}}' | grep -c "^${QS_T}-switch" || true)" 0
expect_equal "query.env matches the running container after rollback" "$(cksum < "$DOCKER_CONFIG_DIR/query/query.env")" "$unhealthy_sum"
expect_equal "query.env URLs still java after rollback" "$(env_value_of "$DOCKER_CONFIG_DIR/query/query.env" HPDS_AUTH_URL)" "http://${JAVA_T}:8080/PIC-SURE"
[[ ! -e "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" ]] && pass "no backup left by the rollback" || check_failed "no backup left by the rollback"
reset_query_env

echo "--- migrate while running recreates hpds-rs"
cp "$FIXTURE/columnMeta.javabin" "$FIXTURE/allObservationsStore.javabin" "$DOCKER_CONFIG_DIR/hpds/"
rs_before="$(docker container inspect -f '{{.Id}}' ${RS_T})"
"$AIO/load.sh" migrate
[[ "$rs_before" != "$(docker container inspect -f '{{.Id}}' ${RS_T})" ]] && pass "hpds-rs recreated after migrate" || check_failed "hpds-rs recreated after migrate"
expect_equal "hpds-rs healthy after migrate" "$(docker container inspect -f '{{.State.Health.Status}}' ${RS_T})" healthy
expect_equal "migrate key mode" "$(stat -f '%Lp' "$DOCKER_CONFIG_DIR/hpds-rs/encryption_key")" 600
"$AIO/status.sh"

echo "--- partitioned migrate writes one store the service opens"
part_out="$WORK/partitioned"
java_etl="$(bash -c 'source "$1/lib.sh" && find_java_etl_image' find-java-etl "$AIO")"
[[ -n "$java_etl" ]] && pass "Java ETL image found as load.sh finds it ($java_etl)" || check_failed "Java ETL image found as load.sh finds it"
migrate_partitioned() {
  JAVA_ETL_IMAGE="$java_etl" CONTAINER_PREFIX="${PREFIX}etl-mig-t" "$RS_ROOT/bin/migrate-javabins" "$PARTITIONED_FIXTURE" "$@"
}
partition_count="$(find "$PARTITIONED_FIXTURE" -mindepth 2 -maxdepth 2 -name allObservationsStore.javabin | wc -l | tr -d ' ')"
if migrate_partitioned "$part_out" > "$WORK/partitioned.log" 2>&1; then
  pass "partitioned migrate succeeded"
else
  check_failed "partitioned migrate succeeded"; tail -5 "$WORK/partitioned.log"
fi
[[ -f "$part_out/pheno.index" && -f "$part_out/.generation" ]] && pass "one store with its marker" || check_failed "one store with its marker"
expect_equal "no partition directories in the output" "$(find "$part_out" -mindepth 1 -maxdepth 1 -type d ! -name '.*' | wc -l | tr -d ' ')" 0
expect_equal "partitioned key mode" "$(stat -f '%Lp' "$part_out/encryption_key")" 600
docker run -d --name ${PART_T} --network ${PICSURE_T} -v "$part_out:/opt/local/hpds:ro" \
  -e HPDS_REQUIRE_AUTHORIZATION_FILTER=true "${HPDS_RS_IMAGE:-hms-dbmi/pic-sure-hpds-rs:LATEST}" >/dev/null
waited=0
until [[ "$(docker container inspect -f '{{.State.Health.Status}}' ${PART_T})" == healthy || "$waited" -ge 60 ]]; do
  sleep 1
  waited=$((waited + 1))
done
expect_equal "partitioned store healthy" "$(docker container inspect -f '{{.State.Health.Status}}' ${PART_T})" healthy
count_in() {
  local consents="$1"
  docker exec ${PROBE_T} wget -q -T 10 -O - --header 'Content-Type: application/json' \
    --post-data "{\"query\":{\"expectedResultType\":\"COUNT\",\"select\":[],\"userConsents\":[$consents]}}" \
    "http://${PART_T}:8080/PIC-SURE/query/sync" 2>&1
}
all_consents="$(find "$PARTITIONED_FIXTURE" -mindepth 2 -maxdepth 2 -name allObservationsStore.javabin \
  | sed 's|/allObservationsStore.javabin$||; s|.*/||; s|.*|{"value":"&"}|' | paste -sd, -)"
one="$(count_in '{"value":"partition1"}')"
every="$(count_in "$all_consents")"
[[ "$one" =~ ^[0-9]+$ && "$every" =~ ^[0-9]+$ && "$one" -gt 0 && "$every" -gt "$one" ]] \
  && pass "consents select partitions ($one of $every patients in partition1, $partition_count partitions)" \
  || check_failed "consents select partitions (partition1 '$one', all '$every')"
docker rm -f ${PART_T} >/dev/null
mkdir -p "$WORK/format1/partition1"
touch "$WORK/format1/partition1/pheno.index"
if FORCE=1 migrate_partitioned "$WORK/format1" > "$WORK/format1.log" 2>&1; then
  check_failed "a format 1 partition layout is refused"
else
  pass "a format 1 partition layout is refused"
fi
expect_equal "the refused directory is unchanged" "$(find "$WORK/format1" | sort | xargs)" "$WORK/format1 $WORK/format1/partition1 $WORK/format1/partition1/pheno.index"

echo "--- up keeps the old hpds-rs when the new one is unhealthy"
old_rs_id="$(docker container inspect -f '{{.Id}}' ${RS_T})"
cp -p "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env" "$WORK/hpds-rs.env.good"
sed -i.bak 's/^ID_BATCH_SIZE=.*/ID_BATCH_SIZE=not-a-number/' "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env"
rm -f "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env.bak"
if HEALTH_TIMEOUT=30 "$AIO/up.sh" >/dev/null 2>&1; then check_failed "up fails when the new container is unhealthy"; else pass "up fails when the new container is unhealthy"; fi
expect_equal "old hpds-rs still running" "$(docker container inspect -f '{{.Id}} {{.State.Running}}' ${RS_T})" "$old_rs_id true"
expect_equal "old hpds-rs still healthy" "$(docker container inspect -f '{{.State.Health.Status}}' ${RS_T})" healthy
expect_equal "no leftover hpds-rs temporary containers" "$(docker ps -a --format '{{.Names}}' | grep -c "^${RS_T}-" || true)" 0
cp -p "$WORK/hpds-rs.env.good" "$DOCKER_CONFIG_DIR/hpds-rs/hpds-rs.env"

echo "--- a replaced hpds-rs keeps serving its old address during the grace period"
ip_on_picsure() {
  docker container inspect -f "{{(index .NetworkSettings.Networks \"${PICSURE_T}\").IPAddress}}" "$1"
}
probe() {
  docker run --rm --network ${PICSURE_T} alpine:3.20 sh -c "$1" 2>&1
}
old_rs_ip="$(ip_on_picsure ${RS_T})"
HPDS_RS_SWAP_GRACE_SECONDS=30 "$AIO/up.sh" > "$WORK/up-grace.log" 2>&1 &
up_pid=$!
waited=0
until [[ "$(docker container inspect -f '{{.Name}}' ${RS_T}-old 2>/dev/null || true)" == "/${RS_T}-old" ]] \
  && [[ "$(docker container inspect -f '{{.Id}}' ${RS_T} 2>/dev/null || true)" != "$old_rs_id" ]]; do
  [[ "$waited" -lt 90 ]] || break
  sleep 1
  waited=$((waited + 1))
done
expect_equal "old container renamed and still running" "$(docker container inspect -f '{{.Id}} {{.State.Running}}' ${RS_T}-old 2>/dev/null || echo absent)" "$old_rs_id true"
expect_equal "old container keeps its address" "$(ip_on_picsure ${RS_T}-old 2>/dev/null || echo absent)" "$old_rs_ip"
expect_equal "old address still answers health" "$(probe "wget -q -T 5 -O - http://$old_rs_ip:8080/actuator/health" | jq -r .status 2>/dev/null)" UP
new_rs_ip="$(ip_on_picsure ${RS_T})"
[[ -n "$new_rs_ip" && "$new_rs_ip" != "$old_rs_ip" ]] && pass "replacement has its own address" || check_failed "replacement has its own address"
expect_equal "the name resolves to the replacement" "$(probe "getent hosts ${RS_T}" | awk '{print $1}')" "$new_rs_ip"
if wait "$up_pid"; then pass "up finished after the grace period"; else check_failed "up finished after the grace period"; cat "$WORK/up-grace.log"; fi
expect_equal "old container removed after the grace period" "$(docker container inspect ${RS_T}-old >/dev/null 2>&1 && echo present || echo gone)" gone
[[ "$old_rs_id" != "$(docker container inspect -f '{{.Id}}' ${RS_T})" ]] && pass "up swapped in a healthy replacement" || check_failed "up swapped in a healthy replacement"
expect_equal "replacement healthy" "$(docker container inspect -f '{{.State.Health.Status}}' ${RS_T})" healthy
expect_equal "replacement restarts always" "$(docker container inspect -f '{{.HostConfig.RestartPolicy.Name}}' ${RS_T})" always
expect_equal "no leftover hpds-rs temporary containers after the swap" "$(docker ps -a --format '{{.Names}}' | grep -c "^${RS_T}-" || true)" 0

echo "--- down while qs points at rust"
"$AIO/switch.sh" rust
"$AIO/down.sh"
expect_equal "hpds-rs removed" "$(docker container inspect ${RS_T} >/dev/null 2>&1 && echo present || echo gone)" gone
expect_equal "qs back on java" "$(env_of ${QS_T} | sed -n 's/^HPDS_AUTH_URL=//p')" "http://${JAVA_T}:8080/PIC-SURE"
[[ -f "$DOCKER_CONFIG_DIR/hpds-rs/pheno.index" ]] && pass "down kept the store" || check_failed "down kept the store"
"$AIO/down.sh" >/dev/null && pass "down is idempotent" || check_failed "down is idempotent"

echo "--- down with no query service container restores the file"
"$AIO/up.sh"
"$AIO/switch.sh" rust
mv "$DOCKER_CONFIG_DIR/query/query.env.pre-rust" "$DOCKER_CONFIG_DIR/query/query.env.java-backup"
docker rm -f ${QS_T} >/dev/null
"$AIO/down.sh"
expect_equal "hpds-rs removed by the second down" "$(docker container inspect ${RS_T} >/dev/null 2>&1 && echo present || echo gone)" gone
cmp -s "$WORK/query.env.original" "$DOCKER_CONFIG_DIR/query/query.env" && pass "query.env restored from an earlier-named backup with no query service" || check_failed "query.env restored from an earlier-named backup with no query service"
[[ -e "$DOCKER_CONFIG_DIR/query/query.env.java-backup.used" ]] && pass "earlier-named backup retired" || check_failed "earlier-named backup retired"

echo "--- unset DOCKER_CONFIG_DIR"
unset_output="$(env -u DOCKER_CONFIG_DIR "$AIO/status.sh" 2>&1 || true)"
[[ "$unset_output" == *"DOCKER_CONFIG_DIR is not set"* ]] && pass "clear error when unset" || check_failed "clear error when unset"

echo
if [[ "$failures" -eq 0 ]]; then echo "all checks passed"; else echo "$failures check(s) failed"; exit 1; fi
