"""Checks `/search` answers of service B against service A, with and without Accept-Encoding.

Every entry of kind `search` is sent to A with no Accept-Encoding header at all, which is the
reference answer. B must give the same status, every header except `Date`, and the same body
when it is asked the same way. B is then asked with each value in ACCEPTS:

- `--gzip off` (the service default, `HPDS_SEARCH_GZIP=off`): every answer must equal the
  reference exactly, status, headers and body, so no `Content-Encoding` and no `Vary`.
- `--gzip on`: the body, gunzipped when `Content-Encoding` is gzip, must equal the reference;
  gzip must be used exactly when the value accepts it and the reference body is at least
  MIN_GZIP_BYTES long; `Content-Length` must match the bytes sent; `Vary: Accept-Encoding`
  must be set exactly on bodies of at least MIN_GZIP_BYTES; `Content-Type` stays JSON.

Usage: search-encoding.py [--host HOST] [--gzip on|off] <port-a> <port-b> <corpus-dir>...

Prints one line per differing entry and a summary; exits 1 when any entry differs.
"""
import gzip
import http.client
import json
import os
import sys

ACCEPTS = [("gzip", True), ("gzip, deflate, x-gzip", True), ("GZIP", True), ("*", True),
           ("gzip;q=0", False), ("identity", False), ("deflate, br", False), ("", False)]
MIN_GZIP_BYTES = 1400


class Client:
    """One kept-alive connection per port on one host."""

    def __init__(self, host):
        self.host = host
        self.conns = {}

    def search(self, port, body, accept=None):
        """POSTs `body` to `/PIC-SURE/search`, sending Accept-Encoding only when `accept` is
        not None, and returns (status, sorted headers without Date, body bytes). A kept-alive
        connection the server has dropped is reopened once; a second failure raises."""
        data = body.encode()
        for attempt in range(2):
            conn = self.conns.setdefault(port, http.client.HTTPConnection(self.host, port, timeout=120))
            try:
                conn.putrequest("POST", "/PIC-SURE/search", skip_accept_encoding=True)
                conn.putheader("Content-Type", "application/json")
                conn.putheader("Content-Length", str(len(data)))
                if accept is not None:
                    conn.putheader("Accept-Encoding", accept)
                conn.endheaders(data)
                resp = conn.getresponse()
                payload = resp.read()
                break
            except (http.client.HTTPException, ConnectionError):
                conn.close()
                self.conns.pop(port, None)
                if attempt == 1:
                    raise
        headers = sorted((k.lower(), v) for k, v in resp.getheaders() if k.lower() != "date")
        return resp.status, headers, payload


def check_on(reference, answer, accept, wants):
    """Returns the problems of one gzip-enabled answer against the reference."""
    s_ref, _, b_ref = reference
    status, headers, body = answer
    hd = dict(headers)
    gz = hd.get("content-encoding") == "gzip"
    plain = gzip.decompress(body) if gz else body
    problems = []
    if status != s_ref or plain != b_ref:
        problems.append(f"{accept!r}: body differs after decompression")
    if gz != (wants and len(b_ref) >= MIN_GZIP_BYTES):
        problems.append(f"{accept!r}: gzip={gz} for {len(b_ref)}B")
    if hd.get("content-length") != str(len(body)):
        problems.append(f"{accept!r}: content-length {hd.get('content-length')} for {len(body)}B")
    if (hd.get("vary") == "Accept-Encoding") != (len(b_ref) >= MIN_GZIP_BYTES):
        problems.append(f"{accept!r}: vary={hd.get('vary')} for {len(b_ref)}B")
    if hd.get("content-type") != "application/json":
        problems.append(f"{accept!r}: content-type {hd.get('content-type')}")
    return problems


def search_entries(corpus):
    """Yields (file name, request JSON text) for every search entry of a corpus directory."""
    for name in sorted(os.listdir(corpus)):
        if not name.endswith(".json") or name == "manifest.json":
            continue
        with open(os.path.join(corpus, name)) as f:
            entry = json.load(f)
        if entry.get("kind") == "search":
            yield name, json.dumps(entry["request"])


def main():
    """Parses the arguments, checks every corpus and sets the exit status."""
    args = sys.argv[1:]
    host, mode = "127.0.0.1", "off"
    while args and args[0].startswith("--"):
        if args[0] == "--host":
            host = args[1]
        elif args[0] == "--gzip":
            mode = args[1]
        else:
            sys.exit(f"unknown option {args[0]}")
        args = args[2:]
    if mode not in ("on", "off"):
        sys.exit("--gzip takes on or off")
    port_a, port_b = int(args[0]), int(args[1])
    client = Client(host)
    entries = requests = differ = 0
    for corpus in args[2:]:
        for name, body in search_entries(corpus):
            entries += 1
            reference = client.search(port_a, body)
            problems = []
            plain = client.search(port_b, body)
            if plain != reference:
                problems.append(
                    f"no Accept-Encoding: A {reference[0]} {reference[1]} {len(reference[2])}B, "
                    f"B {plain[0]} {plain[1]} {len(plain[2])}B"
                )
            for accept, wants in ACCEPTS:
                answer = client.search(port_b, body, accept)
                requests += 1
                if mode == "on":
                    problems.extend(check_on(reference, answer, accept, wants))
                elif answer != reference:
                    problems.append(f"{accept!r}: answer differs from the reference ({answer[1]})")
            if problems:
                differ += 1
                print(f"DIFF {os.path.basename(corpus)}/{name}: " + "; ".join(problems))
    print(f"search-encoding gzip={mode}: {entries} search entries, {requests} encoded requests, {differ} differ")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()
