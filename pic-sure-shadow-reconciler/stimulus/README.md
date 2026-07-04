# Seeded stimulus set

`seed-requests.sh` drives a deliberate, minimal allow + reject corpus through the live AIO
gateway while it is running in `observe` mode, so that the reconciler's decision-coverage
check (`Report.passesExitGate()`) is guaranteed to have at least one allow and one reject
decision per canonical route it exercises, rather than relying on organic traffic to happen
to produce both.

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
that every canonical route observed in the run saw **both** an allow decision
(`active`/`allow`) and a reject decision (`inactive`/`deny`) from the WF side, in addition to
zero divergences. `seed-requests.sh`'s reject cases (`NOPRIV_TOKEN`, `EXPIRED_TOKEN`,
malformed body) exist specifically to guarantee that every route it touches produces at
least one `inactive`/`deny` `SHADOW_WF` line, so the exit gate is actually exercised rather
than vacuously passing on allow-only traffic.

Always read the printed `-- decision coverage --` section of the reconciler's report (or
inspect `Report.coverage()` programmatically) to confirm both decisions were seen per route,
not just that `EXIT GATE: PASS` was printed.
