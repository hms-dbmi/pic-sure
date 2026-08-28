#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
run_id="cleanup$PPID$$"
label="org.pic-sure.operations-compatibility=$run_id"
network="operations-compat-$run_id"
fixture_image="busybox@sha256:dc2d74b28e4cf8984fa52af1f39bc7c3d9c73760b41a74d629f5d11b1ab28616"

emergency_cleanup() {
    "$test_dir/cleanup-resources.sh" "$run_id" >/dev/null 2>&1 || true
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
echo "PASS: failure-path cleanup removed every labeled resource"
