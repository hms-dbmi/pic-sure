#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$repo_root/tests/operations-binary-compatibility"
selection="${1:-all}"
tmp_parent="${TMPDIR:-/tmp}"
tmp_parent="${tmp_parent%/}"
tmp_root=""
run_id=""

cleanup() {
    if [[ -n "$run_id" ]] && command -v docker >/dev/null 2>&1; then
        "$test_dir/cleanup-resources.sh" "$run_id" >/dev/null 2>&1 || true
    fi
    if [[ -n "$tmp_root" && -d "$tmp_root" && "${KEEP_COMPAT_TEMP:-false}" != true ]]; then
        case "$tmp_root" in
            "$tmp_parent"/operations-binary-*) find "$tmp_root" -depth -delete ;;
            *) echo "Refusing to remove unexpected temporary directory: $tmp_root" >&2 ;;
        esac
    elif [[ -n "$tmp_root" && -d "$tmp_root" ]]; then
        echo "Compatibility diagnostics retained at $tmp_root" >&2
    fi
}
trap cleanup EXIT INT TERM

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

python3 -m unittest discover -v -s "$test_dir" -p 'test_*.py'
PYTHONOPTIMIZE=1 python3 -m unittest discover -v -s "$test_dir" -p 'test_*.py'
python3 "$test_dir/run.py" "$repo_root" "$tmp_root" "$selection"
