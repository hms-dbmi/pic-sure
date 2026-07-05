# Seeded stimulus set

`seed-requests.sh` drives a deliberate, minimal allow + reject corpus through the live AIO
gateway while it is running in `observe` mode, so that the reconciler's decision-coverage
check (`Report.passesExitGate()`) is guaranteed to have at least one allow and one reject
decision **per canonical route in the reference mapping**, rather than relying on organic
traffic to happen to produce both.

## Route universe (every canonical route in the reference mapping)

The route universe is the reconciler's own reference mapping
(`pic-sure-shadow-reconciler/src/main/resources/target-service-mapping.yml`) — the script does
not invent routes. That mapping's canonical routes are `/query/` and `/search/`, and the seed
exercises both with allow- and reject-shaped requests:

- **`/query/`** — allow-shaped (`VALID_TOKEN`) and reject-shaped (`NOPRIV_TOKEN`,
  `EXPIRED_TOKEN`) POSTs to `/picsure/query/sync` **and** `/v3/query/sync` (the cosmetic and
  the decision-affecting `/v3` variant both canonicalize to `/query/sync`).
- **`/search/`** — allow-shaped and reject-shaped POSTs to `/picsure/search/{resourceId}` (a
  placeholder UUID; the introspection decision is path+token based, so no live resource is
  needed).

A reference-mapping route that genuinely **cannot** be exercised with the three tokens alone
(for example one that needs a real, pre-existing resource or query id) is printed as
`SKIPPED <route> - <reason>` via the script's `skip_route` helper and **left out** of the
routes file, rather than being silently omitted — so the gate never demands coverage the seed
cannot produce. As of the current mapping, both `/query/` and `/search/` are auth-path
exercisable, so none are skipped.

## Shared route source of truth (`--routes`)

The script writes the canonical routes it covered to `ROUTES_FILE` (default
`stimulus/canonical-routes.txt`, git-ignored — it is generated). Pass that same file to the
reconciler so its exit gate requires coverage of exactly the routes the seed drove — a route
listed there but absent from **both** logs then fails the gate instead of passing unevaluated:

```bash
java -jar pic-sure-shadow-reconciler/target/pic-sure-shadow-reconciler-<version>.jar \
  --gw shadow-gw.jsonl --wf shadow-wf.jsonl --routes pic-sure-shadow-reconciler/stimulus/canonical-routes.txt
```

The stimulus and the gate thereby share one route universe.

## Required tokens

The script requires three bearer tokens, minted against the AIO test Okta/PSAMA setup
(see `pic-sure-all-in-one/` and the `ras-specialist` skill for the RAS/Okta token flow if
the tokens are RAS-brokered):

- `VALID_TOKEN` — an authenticated token for a user with privileges on the target resource.
  Used to produce `active` (introspection allow) decisions.
- `NOPRIV_TOKEN` — an authenticated token for a user with no privileges on the target
  resource (valid signature, valid session, but PSAMA denies the query). Used to produce
  `inactive` (introspection reject) decisions.
- `EXPIRED_TOKEN` — a token whose session/token has expired or been revoked. Used to produce
  a second, distinct `inactive` reject path (auth failure rather than authorization failure).

All three are ordinary bearer tokens accepted by WildFly's `JWTFilter` / PSAMA today —
minting them does not require any gateway changes.

## Preconditions

Before running this script:

1. The gateway must be deployed with `gateway.auth.mode=observe` (`GatewayAuthMode.OBSERVE`
   per the plan's reconciliation note — builds + logs the introspection request and forwards
   it unchanged; it never calls PSAMA itself and never mutates the request).
2. WildFly must be running with `PICSURE_SHADOW_LOGGING=true` so `ShadowLog` emits `SHADOW_WF`
   lines alongside its real (sole) enforcement decision.
3. Both shadow loggers must be writing to files or streams that can be collected afterward
   (see the observe-window runbook for the expected file names,
   `shadow-gw.jsonl` / `shadow-wf.jsonl`).

## Verifying coverage before trusting "0 divergences"

A run with zero `DIVERGENCE` verdicts is **not** sufficient evidence of parity by itself — if
every request that ran through the observe window happened to be an allow, the reconciler
never saw the gateway's and WildFly's reject-path behavior and cannot know whether they'd
agree on a reject. This is exactly what `Report.passesExitGate()` checks for: it requires
that every required canonical route saw **both** an allow decision (`active`/`allow`) and a
reject decision (`inactive`/`deny`) from paired records, in addition to zero divergences and
**zero unpaired records**. `seed-requests.sh`'s reject cases (`NOPRIV_TOKEN`, `EXPIRED_TOKEN`)
exist specifically to guarantee that every route it touches produces at least one
`inactive`/`deny` `SHADOW_WF` line, so the exit gate is actually exercised rather than
vacuously passing on allow-only traffic.

Coverage is now credited **only from fully-paired, non-divergent pairs** — a WildFly record
with no gateway counterpart grants no coverage and instead counts as `UNPAIRED`, which fails
the gate. This closes the old false-PASS where an all-`UNPAIRED` run (the gateway emitted
nothing) still printed `PASS` on WF-derived coverage.

Always read the printed report before trusting `EXIT GATE: PASS`:

- `-- decision coverage --` — confirm both decisions were seen per route.
- `-- unpaired records --` — must be absent (any unpaired record fails the gate).
- `path-only matches: N` — POSTs whose gateway `query` was absent by design (OBSERVE never
  buffers POST bodies) so only path + decision could be compared. These still count toward
  coverage, but the count tells you how much of the evidence is path-only rather than a full
  query match — factor it into your sign-off.

## Body shape for /query/* seeds (learned live, 2026-07-05)

The `/query/*` (non-v3) request bodies MUST carry a **valid-UUID-shaped** `resourceUUID`: WildFly's
`JWTFilter.prepareRequestMap()` runs `UUID.fromString()` on it *before* introspection, so a non-UUID
placeholder 500s the WAR pre-introspection — no `SHADOW_WF` line is emitted and the request lands as
`UNPAIRED` (which fails the gate). A valid-but-nonexistent UUID is fine (the resource lookup returns
null and the queryFormat enrichment is skipped). The v3 path does not read `resourceUUID` at all.

`GATEWAY_URL` is the ingress (httpd) origin: httpd strips the leading `/picsure/` before proxying to
the gateway, so the v3 variant is addressed as `/picsure/v3/query/sync`. The open-access posts are
opt-in via `OPEN_ACCESS=true` — against a deployment with open access disabled they cannot produce a
pairable WildFly decision and would only add `UNPAIRED` noise.
