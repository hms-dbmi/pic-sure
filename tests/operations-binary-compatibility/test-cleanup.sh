#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
run_id="cleanup$PPID$$"
label="org.pic-sure.operations-compatibility=$run_id"
network="operations-compat-$run_id"
verdict_run_id="verdict$PPID$$"
verdict_label="org.pic-sure.operations-compatibility=$verdict_run_id"
verdict_network="operations-compat-$verdict_run_id"
verdict_container="operations-compat-$verdict_run_id-unlabeled"
fixture_image="busybox@sha256:dc2d74b28e4cf8984fa52af1f39bc7c3d9c73760b41a74d629f5d11b1ab28616"

emergency_cleanup() {
    "$test_dir/cleanup-resources.sh" "$run_id" >/dev/null 2>&1 || true
    docker container rm --force "$verdict_container" >/dev/null 2>&1 || true
    "$test_dir/cleanup-resources.sh" "$verdict_run_id" >/dev/null 2>&1 || true
}
trap emergency_cleanup EXIT INT TERM

(
    trap emergency_cleanup EXIT
    docker network create --label "$label" "$network" >/dev/null
    docker run -d --name "operations-compat-$run_id-fixture" --label "$label" --network "$network" "$fixture_image" sleep 300 >/dev/null
    false
) || true

if [[ -n "$(docker container ls --all --quiet --filter "label=$label")" ]]; then
    echo "failure-path cleanup test left a container" >&2
    exit 1
fi
if docker network inspect "$network" >/dev/null 2>&1; then
    echo "failure-path cleanup test left a network" >&2
    exit 1
fi

docker network create --label "$verdict_label" "$verdict_network" >/dev/null
docker run -d --name "$verdict_container" --network "$verdict_network" "$fixture_image" sleep 300 >/dev/null
if verdict_output="$("$test_dir/cleanup-resources.sh" "$verdict_run_id" 2>&1)"; then
    echo "cleanup verdict test expected a nonzero exit while an unlabeled container held the network" >&2
    exit 1
fi
if [[ "$verdict_output" != *"cleanup left compatibility network $verdict_network"* ]]; then
    echo "cleanup verdict test did not retain the network diagnostic: $verdict_output" >&2
    exit 1
fi
docker container rm --force "$verdict_container" >/dev/null
"$test_dir/cleanup-resources.sh" "$verdict_run_id"

echo "PASS: failure-path cleanup removed every labeled resource and reported a forced cleanup failure"
