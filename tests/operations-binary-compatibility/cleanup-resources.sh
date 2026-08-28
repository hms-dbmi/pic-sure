#!/usr/bin/env bash
set -euo pipefail

run_id="${1:-}"
if [[ ! "$run_id" =~ ^[A-Za-z0-9]+$ ]]; then
    echo "cleanup run ID must contain only letters and digits" >&2
    exit 2
fi

label="org.pic-sure.operations-compatibility=$run_id"
network="operations-compat-$run_id"

for _attempt in {1..20}; do
    while IFS= read -r container; do
        [[ -z "$container" ]] || docker container rm --force "$container" >/dev/null 2>&1 || true
    done < <(docker container ls --all --quiet --filter "label=$label")
    [[ -z "$(docker container ls --all --quiet --filter "label=$label")" ]] && break
    sleep 0.1
done

for _attempt in {1..20}; do
    docker network rm "$network" >/dev/null 2>&1 || true
    ! docker network inspect "$network" >/dev/null 2>&1 && break
    sleep 0.1
done

remaining="$(docker container ls --all --quiet --filter "label=$label")"
if [[ -n "$remaining" ]]; then
    echo "cleanup left compatibility containers for $run_id: $remaining" >&2
    exit 1
fi
if docker network inspect "$network" >/dev/null 2>&1; then
    echo "cleanup left compatibility network $network" >&2
    exit 1
fi
