#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker_binary="${BANNER_FEED_DOCKER_BIN:-docker}"
docker_timeout="${BANNER_FEED_DOCKER_TIMEOUT_SECONDS:-30}"
run_id="cleanup$PPID$$"
label="org.pic-sure.banner-feed-compatibility=$run_id"
network="banner-feed-compat-$run_id"
fixture_image="busybox@sha256:dc2d74b28e4cf8984fa52af1f39bc7c3d9c73760b41a74d629f5d11b1ab28616"
fixture_tag="banner-feed-cleanup-fixture:$run_id"

docker_command() {
    python3 "$test_dir/../operations-binary-compatibility/bounded_command.py" "$docker_timeout" "$docker_binary" "$@"
}

emergency_cleanup() {
    "$test_dir/cleanup-resources.sh" "$run_id" >/dev/null 2>&1 || true
}
trap emergency_cleanup EXIT INT TERM

(
    trap emergency_cleanup EXIT
    docker_command network create --label "$label" "$network" >/dev/null
    docker_command run -d --name "banner-feed-compat-$run_id-fixture" --label "$label" \
        --network "$network" "$fixture_image" sleep 300 >/dev/null
    docker_command container commit --change "LABEL $label" \
        "banner-feed-compat-$run_id-fixture" "$fixture_tag" >/dev/null
    false
) || true

if [[ -n "$(docker_command container ls --all --quiet --filter "label=$label")" \
    || -n "$(docker_command network ls --quiet --filter "name=^${network}$")" \
    || -n "$(docker_command image ls --quiet --filter "label=$label")" ]]; then
    echo "failure-path cleanup left a Ticket 18 resource" >&2
    exit 1
fi
echo "PASS: Ticket 18 forced-failure cleanup removed every labeled resource"
