#!/usr/bin/env python3

import os
import signal
import subprocess
import sys


class CommandSignal(Exception):
    def __init__(self, signum):
        super().__init__(signum)
        self.signum = signum


def terminate_process_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def run(command, timeout):
    process = None
    received_signal = None
    handled_signals = (signal.SIGINT, signal.SIGTERM)
    previous_handlers = {}
    previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, handled_signals)

    def handle_signal(signum, _frame):
        raise CommandSignal(signum)

    for handled_signal in handled_signals:
        previous_handlers[handled_signal] = signal.signal(handled_signal, handle_signal)
    try:
        process = subprocess.Popen(command, start_new_session=True)
        signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
        try:
            status = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            terminate_process_group(process)
            print(
                f"command timed out after {timeout} seconds: {' '.join(command)}",
                file=sys.stderr,
            )
            status = 124
        except CommandSignal as error:
            received_signal = error.signum
            for handled_signal in handled_signals:
                signal.signal(handled_signal, signal.SIG_IGN)
            terminate_process_group(process)
            status = 128 + received_signal
    except CommandSignal as error:
        received_signal = error.signum
        for handled_signal in handled_signals:
            signal.signal(handled_signal, signal.SIG_IGN)
        if process is not None:
            terminate_process_group(process)
        status = 128 + received_signal
    except OSError as error:
        print(f"could not run {' '.join(command)}: {error}", file=sys.stderr)
        status = 127
    finally:
        signal.pthread_sigmask(signal.SIG_BLOCK, handled_signals)
        for handled_signal, previous_handler in previous_handlers.items():
            signal.signal(handled_signal, previous_handler)
        signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)

    if received_signal is not None:
        signal.signal(received_signal, signal.SIG_DFL)
        os.kill(os.getpid(), received_signal)
    return status


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
