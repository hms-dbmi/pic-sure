#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker_binary="${OPERATIONS_COMPAT_DOCKER_BIN:-docker}"
docker_timeout="${OPERATIONS_COMPAT_DOCKER_TIMEOUT_SECONDS:-30}"

docker_command() {
    python3 "$test_dir/bounded_command.py" "$docker_timeout" "$docker_binary" "$@"
}

run_id="${1:-}"
if [[ ! "$run_id" =~ ^[A-Za-z0-9]+$ ]]; then
    echo "cleanup run ID must contain only letters and digits" >&2
    exit 2
fi

label="org.pic-sure.operations-compatibility=$run_id"
network="operations-compat-$run_id"

for _attempt in {1..20}; do
    containers="$(docker_command container ls --all --quiet --filter "label=$label")"
    while IFS= read -r container; do
        if [[ -n "$container" ]]; then
            remove_status=0
            docker_command container rm --force "$container" >/dev/null || remove_status=$?
            [[ $remove_status -ne 124 ]] || exit "$remove_status"
        fi
    done <<< "$containers"
    containers="$(docker_command container ls --all --quiet --filter "label=$label")"
    [[ -n "$containers" ]] || break
    sleep 0.1
done

for _attempt in {1..20}; do
    network_status=0
    network_output="$(docker_command network rm "$network" 2>&1)" || network_status=$?
    if [[ $network_status -eq 124 ]]; then
        echo "$network_output" >&2
        exit "$network_status"
    fi
    inspect_status=0
    inspect_output="$(docker_command network inspect "$network" 2>&1)" || inspect_status=$?
    if [[ $inspect_status -eq 124 ]]; then
        echo "$inspect_output" >&2
        exit "$inspect_status"
    fi
    [[ $inspect_status -eq 0 ]] || break
    sleep 0.1
done

remaining="$(docker_command container ls --all --quiet --filter "label=$label")"
if [[ -n "$remaining" ]]; then
    echo "cleanup left compatibility containers for $run_id: $remaining" >&2
    exit 1
fi
inspect_status=0
inspect_output="$(docker_command network inspect "$network" 2>&1)" || inspect_status=$?
if [[ $inspect_status -eq 124 ]]; then
    echo "$inspect_output" >&2
    exit "$inspect_status"
fi
if [[ $inspect_status -eq 0 ]]; then
    echo "cleanup left compatibility network $network" >&2
    exit 1
fi
