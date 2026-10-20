# pic-sure-hpds-query-service

DB-free single HPDS ingress (Phase 4) + open-path aggregate obfuscation (Phase 5).

## Rollback (aggregate fold — Phase 5)
The legacy `pic-sure-aggregate-data-sharing-resource` WAR remains deployed on WildFly through Phase 6 and is
removed only in Phase 7. The open-path obfuscation now runs here (`/hpds/open/query/sync`). To revert to
the WAR (BDC/prod only — the AIO does not deploy the aggregate WAR): repoint the gateway's `/hpds/open/**`
route back to WildFly's aggregate WAR context and redeploy. This service's aggregate endpoints keep running
but receive no traffic. (In single-HPDS AIO there is nothing to roll back to.)

## Rust HPDS routing

The query service can send part of the auth backend's traffic to a Rust HPDS that runs beside Java HPDS. It is
off unless `HPDS_RS_URL` is set and `HPDS_RS_MODE` is `shadow` or `serve`.

| Variable | Default | Meaning |
|---|---|---|
| `HPDS_RS_URL` | unset | Rust HPDS base URL, such as `http://hpds-rs:8080/PIC-SURE`; `HPDS_API_PATH` is appended as for Java. Unset means off whatever the mode. |
| `HPDS_RS_TOKEN` | unset | Service Bearer token sent to Rust on sync queries. Search calls carry no token, as on Java. |
| `HPDS_RS_MODE` | `off` | `off`, `shadow` (Java answers, Rust is mirrored and compared) or `serve` (Rust answers, Java on failure). |
| `HPDS_RS_TYPES` | `COUNT,CROSS_COUNT,CATEGORICAL_CROSS_COUNT,CONTINUOUS_CROSS_COUNT` | Result types routed or mirrored. `SEARCH` and `SEARCH_VALUES` name the two search calls; genomic types are added by name. `PATIENTS`, `DATAFRAME`, `DATAFRAME_TIMESERIES` and `DATAFRAME_PFB` are refused, since they are only served asynchronously. |
| `HPDS_RS_SHADOW_SAMPLE` | `0.1` | Share of eligible calls mirrored in shadow mode. |
| `HPDS_RS_SHADOW_MAX_IN_FLIGHT` | `4` | Mirror calls in flight at once. A sampled call over the limit is dropped and counted, never queued. |
| `HPDS_RS_TIMEOUT_MS` | `30000` | Timeout of each wait in a Rust call; see below for exactly what it bounds. |

Every value is read once at startup. A set `HPDS_RS_URL` that is not an http or https URL with a host, an unknown or
async-only type, a mode other than the three, a sample outside 0 to 1, or a limit or timeout below 1 stops the
service from starting.

**What `HPDS_RS_TIMEOUT_MS` bounds.** The Rust client uses it as the connect timeout, the wait for the response to
start, and each wait for more bytes of the response. It does not bound the whole call: HttpComponents' classic
client has no total deadline, so a Rust HPDS that keeps sending bytes more often than the timeout can hold a call
longer. A wait for a free pooled connection is bounded separately, at 1 second or `HPDS_RS_TIMEOUT_MS` if shorter,
and counts as `pool-exhausted`.

**Kill switch.** Set `HPDS_RS_MODE=off`, or unset `HPDS_RS_URL`, and restart the query service. There is no admin
endpoint.

**What is eligible.** A call reaches Rust only when it is on the `auth` backend, it is a sync query
(`/hpds/auth/query/sync`) or a search call (`/hpds/auth/search`, `/hpds/auth/search/values`), and its type is in
`HPDS_RS_TYPES`. The open backend, async creates, status, result and signed-url always go to Java, so every stored
async result id stays on the backend that made it. Consent scoping runs once before dispatch, so Java and Rust
receive the same scoped request.

**Serve.** Rust answers. A connect error, a timeout, an exhausted pool, a non-2xx status (Rust's by-design refusals
included), a 2xx body the service cannot read, or any error inside the Rust client falls back to Java's answer for
that call, so no Rust failure reaches a user. Rust's answers are read as leniently as Java's: a member the response
model does not declare is ignored.
The Rust client has its own connection pool (100 total, 20 per route) and does not retry. A sync row stored from a
Rust answer holds Rust's query id; nothing reads a sync row's result id back from HPDS, because the sync answer
never returns the row's `picsureId` and status, result and signed-url look rows up only by that id.

**Shadow.** Java answers. After Java's answer is stored, a sample of eligible calls is sent to Rust on a bounded
executor and the two answers are compared. Java's answer is returned whatever Rust does. The mirror reads at most
16 MiB and one byte of Rust's body. Comparisons use these normalizations, named after the equivalence harness rules
where the harness has one:

| Type | Normalization |
|---|---|
| `COUNT`, `VARIANT_COUNT_FOR_QUERY`, any type without a rule | `exact`, byte for byte |
| the four cross counts | `json-key-order`, parsed JSON with object key order ignored; the query service's own name, since the harness compares cross counts as parsed JSON without naming a rule |
| `VARIANT_LIST_FOR_QUERY` | `variant-list-order`, the variants as a multiset |
| `INFO_COLUMN_LISTING` | `info-column-listing-order`, the entries as a multiset |
| `VCF_EXCERPT`, `AGGREGATE_VCF_EXCERPT` | `excerpt-order`, rows as a multiset, comma-joined cells as sets, columns keyed by header |
| `SEARCH` | `search-concept-order,search-category-values-order,search-info-values-order`, field for field with every array (the concept list, category values and info values) compared as a multiset |
| `SEARCH_VALUES` | `search-values-bucket-order`, `page` and `total` exact and `results` as a multiset |

Identical bodies log `none`. Two non-2xx answers compare by status only (`status-only`). A body over 16 MiB on
either side, or a Rust call that got no answer, is `not-compared` with the reason.

**What is logged.** One line per mirrored call and one per serve fallback:

```
hpds_rs_shadow type=COUNT query_id=<picsureId or -> java_status=200 rust_status=200 java_ms=12 rust_ms=8 result=match normalization=none reason=-
hpds_rs_fallback type=COUNT reason=status-4xx rust_status=400 rust_ms=3
```

No line holds a body, a count or a digest of a body, because a short body such as a COUNT can be recovered from
its hash. A mismatch is triaged by re-running the stored query against both backends inside the boundary.

**Metrics** (`/actuator/prometheus` when exposed):

- `hpds_rs_serve_total{type,outcome,reason}`: serve-mode calls, `outcome` `rust` or `fallback`, `reason` the
  fallback reason (`connect-error`, `timeout`, `pool-exhausted`, `io-error`, `client-error`, `status-4xx`,
  `status-5xx`, `status-other`, `unreadable-answer`).
- `hpds_rs_shadow_total{type,result,normalization,reason}`: mirrored calls by `match`, `mismatch`,
  `not-compared` or `error`.
- `hpds_rs_shadow_dropped_total{type,reason}`: sampled calls not mirrored because the in-flight limit was reached.
- `hpds_rs_shadow_in_flight`: mirrors running now.
- `hpds_rs_latency_seconds{backend,mode,type}`: Java and Rust latency on routed and mirrored calls.
- `hpds_rs_mode{mode}`: the mode in effect.

**Audit.** A routed or mirrored call carries `X-PIC-SURE-HPDS-Backend: rust`, `java-fallback` or `java-shadow` on
its response, error responses included: a mirrored call whose Java answer was an error, and a fallback whose Java
call also failed, keep their label. The gateway records the value as the `hpds_backend` metadata of its audit event
when it is one of those three, and always removes the header before the response reaches the client.
