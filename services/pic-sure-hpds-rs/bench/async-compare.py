"""Byte-strict comparison of the async entries of one or more corpora on two services.

Every entry of kind `async` is submitted to both services, polled to its final state and its
result fetched. The two sides must agree on the final `status`, `resourceStatus`,
`sizeInBytes` and error, on the HTTP status of `POST /query/{id}/result`, on every response
header of that call except `Date`, `Connection` and `Keep-Alive`, and on the result body,
compared by SHA-256. `hpds-bench compare` compares CSV rows as a multiset; this check also
pins their order and every byte.

Usage: async-compare.py [--host HOST] <port-a> <port-b> <corpus-dir>...

Prints one line per corpus and up to five differing entries; exits 1 when any entry differs.
Connections are kept alive per worker thread, so the 2 ms status poll does not exhaust the
host's ephemeral ports.
"""
import concurrent.futures
import hashlib
import http.client
import json
import os
import sys
import threading
import time

LOCAL = threading.local()
HOST = "127.0.0.1"
POLL_SECONDS = 0.002
DEADLINE_SECONDS = 300
IN_MEMORY_LIMIT = 256 * 1024


def connection(port):
    """Returns this thread's kept-alive connection to `port`, opening it on first use."""
    pool = getattr(LOCAL, "pool", None)
    if pool is None:
        pool = LOCAL.pool = {}
    if port not in pool:
        pool[port] = http.client.HTTPConnection(HOST, port, timeout=120)
    return pool[port]


def call(port, path, body):
    """POSTs `body` to `path` and returns (status, headers without Date, body bytes).

    A broken kept-alive connection is reopened once; a second failure raises.
    """
    for attempt in range(2):
        conn = connection(port)
        try:
            conn.request("POST", path, body=body, headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            data = resp.read()
            break
        except (http.client.HTTPException, ConnectionError):
            conn.close()
            LOCAL.pool.pop(port, None)
            if attempt == 1:
                raise
    headers = sorted(
        (k.lower(), v)
        for k, v in resp.getheaders()
        if k.lower() not in ("date", "connection", "keep-alive")
    )
    return resp.status, headers, data


def run(port, request):
    """Runs one async request on `port` and returns everything the comparison looks at."""
    status, _, data = call(port, "/PIC-SURE/query", json.dumps(request))
    if status != 200:
        return {"submit": status}
    state = json.loads(data)
    rid = state.get("resourceResultId")
    deadline = time.time() + DEADLINE_SECONDS
    while state.get("resourceStatus") in ("PENDING", "RUNNING") and time.time() < deadline:
        time.sleep(POLL_SECONDS)
        _, _, data = call(port, f"/PIC-SURE/query/{rid}/status", "{}")
        state = json.loads(data)
    out = {
        "status": state.get("status"),
        "resourceStatus": state.get("resourceStatus"),
        "sizeInBytes": state.get("sizeInBytes"),
        "error": state.get("resultMetadata", {}).get("error"),
    }
    rstatus, rheaders, body = call(port, f"/PIC-SURE/query/{rid}/result", "{}")
    out["result_status"] = rstatus
    out["result_headers"] = rheaders
    out["result_sha"] = hashlib.sha256(body).hexdigest()
    out["result_len"] = len(body)
    return out


def entries(corpus):
    """Yields (name, request) for every async entry of a corpus directory, in file order."""
    for name in sorted(os.listdir(corpus)):
        if not name.endswith(".json") or name == "manifest.json":
            continue
        with open(os.path.join(corpus, name)) as f:
            entry = json.load(f)
        if entry.get("kind") == "async":
            yield entry["name"], entry["request"]


def compare(args):
    """Runs one entry on both sides and returns (name, side a, side b)."""
    port_a, port_b, name, request = args
    return name, run(port_a, request), run(port_b, request)


def main():
    """Parses the arguments, compares every corpus and sets the exit status."""
    global HOST
    args = sys.argv[1:]
    if args[:1] == ["--host"]:
        HOST = args[1]
        args = args[2:]
    port_a, port_b = int(args[0]), int(args[1])
    total_differ = 0
    for corpus in args[2:]:
        work = [(port_a, port_b, n, r) for n, r in entries(corpus)]
        differ, success, memory_sized, total_bytes = [], 0, 0, 0
        with concurrent.futures.ThreadPoolExecutor(4) as pool:
            for name, a, b in pool.map(compare, work):
                if a != b:
                    differ.append((name, a, b))
                if a.get("resourceStatus") == "SUCCESS":
                    success += 1
                    total_bytes += a["result_len"]
                    if a["result_len"] <= IN_MEMORY_LIMIT:
                        memory_sized += 1
        total_differ += len(differ)
        print(
            f"async-compare {os.path.basename(corpus)}: {len(differ)} of {len(work)} async entries differ; "
            f"{success} SUCCESS, {memory_sized} at most 256 KiB, {total_bytes} bytes"
        )
        for name, a, b in differ[:5]:
            print(f"  {name}\n    A={a}\n    B={b}")
    sys.exit(1 if total_differ else 0)


if __name__ == "__main__":
    main()
