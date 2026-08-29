#!/usr/bin/env python3

import subprocess
import sys


def run(command, timeout):
    try:
        result = subprocess.run(command, check=False, timeout=timeout)
    except subprocess.TimeoutExpired:
        print(
            f"command timed out after {timeout} seconds: {' '.join(command)}",
            file=sys.stderr,
        )
        return 124
    except OSError as error:
        print(f"could not run {' '.join(command)}: {error}", file=sys.stderr)
        return 127
    return result.returncode


def main():
    if len(sys.argv) < 3:
        print("usage: bounded_command.py <timeout-seconds> <command> [args...]", file=sys.stderr)
        return 2
    try:
        timeout = float(sys.argv[1])
    except ValueError:
        print(f"invalid command timeout: {sys.argv[1]}", file=sys.stderr)
        return 2
    if timeout <= 0:
        print("command timeout must be positive", file=sys.stderr)
        return 2
    return run(sys.argv[2:], timeout)


if __name__ == "__main__":
    raise SystemExit(main())
