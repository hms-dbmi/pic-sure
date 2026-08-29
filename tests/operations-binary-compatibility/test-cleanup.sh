#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker_binary="${OPERATIONS_COMPAT_DOCKER_BIN:-docker}"
docker_timeout="${OPERATIONS_COMPAT_DOCKER_TIMEOUT_SECONDS:-30}"

docker_command() {
    python3 "$test_dir/bounded_command.py" "$docker_timeout" "$docker_binary" "$@"
}

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
    docker_command container rm --force "$verdict_container" >/dev/null 2>&1 || true
    "$test_dir/cleanup-resources.sh" "$verdict_run_id" >/dev/null 2>&1 || true
}
trap emergency_cleanup EXIT INT TERM

(
    trap emergency_cleanup EXIT
    docker_command network create --label "$label" "$network" >/dev/null
    docker_command run -d --name "operations-compat-$run_id-fixture" --label "$label" --network "$network" "$fixture_image" sleep 300 >/dev/null
    false
) || true

if [[ -n "$(docker_command container ls --all --quiet --filter "label=$label")" ]]; then
    echo "failure-path cleanup test left a container" >&2
    exit 1
fi
networks="$(docker_command network ls --quiet --filter "name=^${network}$")"
if [[ -n "$networks" ]]; then
    echo "failure-path cleanup test left a network" >&2
    exit 1
fi

docker_command network create --label "$verdict_label" "$verdict_network" >/dev/null
docker_command run -d --name "$verdict_container" --network "$verdict_network" "$fixture_image" sleep 300 >/dev/null
if verdict_output="$("$test_dir/cleanup-resources.sh" "$verdict_run_id" 2>&1)"; then
    echo "cleanup verdict test expected a nonzero exit while an unlabeled container held the network" >&2
    exit 1
fi
if [[ "$verdict_output" != *"cleanup left compatibility network $verdict_network"* ]]; then
    echo "cleanup verdict test did not retain the network diagnostic: $verdict_output" >&2
    exit 1
fi
docker_command container rm --force "$verdict_container" >/dev/null
"$test_dir/cleanup-resources.sh" "$verdict_run_id"

echo "PASS: failure-path cleanup removed every labeled resource and reported a forced cleanup failure"
