#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$repo_root/tests/operations-binary-compatibility"
selection="${1:-all}"
tmp_parent="${TMPDIR:-/tmp}"
tmp_parent="${tmp_parent%/}"
tmp_root=""
run_id=""
export PYTHONDONTWRITEBYTECODE=1

cleanup() {
    original_status=$?
    cleanup_status=0
    final_status=$original_status
    trap - EXIT INT TERM
    if [[ -n "$run_id" ]] && command -v docker >/dev/null 2>&1; then
        if "$test_dir/cleanup-resources.sh" "$run_id"; then
            cleanup_status=0
        else
            cleanup_status=$?
            echo "Compatibility resource cleanup failed with status $cleanup_status" >&2
        fi
    fi
    if [[ $final_status -eq 0 && $cleanup_status -ne 0 ]]; then
        final_status=$cleanup_status
    fi
    preserve_temp=false
    if [[ "${KEEP_COMPAT_TEMP:-false}" == true \
        || ($final_status -ne 0 && "${KEEP_COMPAT_TEMP_ON_FAILURE:-false}" == true) ]]; then
        preserve_temp=true
    fi
    if [[ -n "$tmp_root" && -d "$tmp_root" && "$preserve_temp" != true ]]; then
        case "$tmp_root" in
            "$tmp_parent"/operations-binary-*) find "$tmp_root" -depth -delete ;;
            *) echo "Refusing to remove unexpected temporary directory: $tmp_root" >&2 ;;
        esac
    elif [[ -n "$tmp_root" && -d "$tmp_root" ]]; then
        echo "Compatibility diagnostics retained at $tmp_root" >&2
    fi
    exit "$final_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

case "$selection" in
    all|lazy-version-recovery|allocator-recovery|overlapping-writers|rollback-pre-version|rollback-pre-allocator|final-http-contract|occurrence-only-rejection) ;;
    *)
        echo "usage: $0 [all|lazy-version-recovery|allocator-recovery|overlapping-writers|rollback-pre-version|rollback-pre-allocator|final-http-contract|occurrence-only-rejection]" >&2
        exit 2
        ;;
esac

tmp_root="$(mktemp -d "$tmp_parent/operations-binary-XXXXXXXX")"
run_id="$(basename "$tmp_root")"
run_id="${run_id#operations-binary-}"

"$test_dir/test-cleanup.sh"
python3 -m unittest discover -v -s "$test_dir" -p 'test_*.py'
PYTHONOPTIMIZE=1 python3 -m unittest discover -v -s "$test_dir" -p 'test_*.py'
python3 "$test_dir/run.py" "$repo_root" "$tmp_root" "$selection"
