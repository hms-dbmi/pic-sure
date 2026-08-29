#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$repo_root/tests/banner-feed-compatibility"
selection="${1:-all}"
tmp_parent="${TMPDIR:-/tmp}"
tmp_parent="${tmp_parent%/}"
tmp_root=""
run_id=""
export PYTHONDONTWRITEBYTECODE=1

cleanup() {
    original_status=$?
    final_status=$original_status
    trap - EXIT INT TERM
    if [[ -n "$run_id" ]] && command -v docker >/dev/null 2>&1; then
        cleanup_status=0
        "$test_dir/cleanup-resources.sh" "$run_id" || cleanup_status=$?
        if [[ $cleanup_status -ne 0 ]]; then
            echo "Ticket 18 resource cleanup failed with status $cleanup_status" >&2
            [[ $final_status -ne 0 ]] || final_status=$cleanup_status
        fi
    fi
    preserve=false
    if [[ "${KEEP_BANNER_FEED_TEMP:-false}" == true \
        || ($final_status -ne 0 && "${KEEP_BANNER_FEED_TEMP_ON_FAILURE:-false}" == true) ]]; then
        preserve=true
    fi
    if [[ -n "$tmp_root" && -d "$tmp_root" && "$preserve" != true ]]; then
        case "$tmp_root" in
            "$tmp_parent"/banner-feed-*) find "$tmp_root" -depth -delete ;;
            *) echo "Refusing to remove unexpected temporary directory: $tmp_root" >&2 ;;
        esac
    elif [[ -n "$tmp_root" && -d "$tmp_root" ]]; then
        echo "Ticket 18 diagnostics retained at $tmp_root" >&2
    fi
    exit "$final_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

case "$selection" in
    all|final-backend-old-frontend|final-backend-final-frontend|old-backend-final-frontend|old-backend-old-frontend-unsafe|supported-rollback-sequence) ;;
    *)
        echo "usage: $0 [all|final-backend-old-frontend|final-backend-final-frontend|old-backend-final-frontend|old-backend-old-frontend-unsafe|supported-rollback-sequence]" >&2
        exit 2
        ;;
esac

tmp_root="$(mktemp -d "$tmp_parent/banner-feed-XXXXXXXX")"
run_id="$(basename "$tmp_root")"
run_id="${run_id#banner-feed-}"

"$test_dir/test-cleanup.sh"
python3 -m unittest discover -v -s "$test_dir" -p 'test_*.py'
PYTHONOPTIMIZE=1 python3 -m unittest discover -v -s "$test_dir" -p 'test_*.py'
python3 "$test_dir/run.py" "$repo_root" "$tmp_root" "$selection"
