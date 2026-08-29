# Banner feed and frontend rollback compatibility proof

This proof runs real Chromium against the exact production frontend container, real Gateway and Operations jars, and disposable MySQL. It verifies the five mixed-version feed cells in [`matrix.tsv`](matrix.tsv) and composes Ticket 17's authoritative seven-cell binary/schema proof through its checked-in `all` entrypoint.

The browser path is:

```text
Chromium -> production frontend httpd -> Gateway -> Operations -> MySQL
                         |-> bundled Node server
```

The proof builds clean Git exports of both frontend generations with each generation's tracked production `Dockerfile`. It adds only a deterministic synthetic `.env` and mounts a generated HTTP-only vhost: `/picsure/*` proxies to Gateway, while all other paths proxy to the frontend's Node server on port 3000. Readiness probes `/login` through that vhost. The browser records real request URLs, response statuses and bodies, rendered markers, and banner-region presence; response mocking and retries are disabled.

## Run it

Docker and Git are required. Before the prerequisite commits are publicly reachable, run from the exact clean integration roots:

```bash
OPERATIONS_COMPAT_SOURCE_ROOT=/path/to/pic-sure \
FRONTEND_COMPAT_SOURCE_ROOT=/path/to/PIC-SURE-Frontend \
AIO_PROOF_SOURCE_ROOT=/path/to/PIC-SURE-Migrations \
BDC_PROOF_SOURCE_ROOT=/path/to/pic-sure-bdc-infrastructure \
tests/banner-feed-compatibility/test.sh all
```

Pass any `cell` value from `matrix.tsv` instead of `all` for a focused run. `all` runs the five feed cells serially, then executes `tests/operations-binary-compatibility/test.sh all`. Source-root overrides must be clean Git repositories containing the exact commits; the AIO and BDC proof roots must also be at the exact required `HEAD`.

Set `KEEP_BANNER_FEED_TEMP=true` to retain all synthetic diagnostics. `KEEP_BANNER_FEED_TEMP_ON_FAILURE=true` retains them only after failure. The workflow uses the latter and uploads the observed matrix, provenance, logs, and failed-cell JSON. Every container and network receives a unique proof label and is removed on success, failure, timeout, signal, or partial startup.

Both frontend generations expose the same `test:vitest`, `lint`, `check`, and `build` scripts. Run them with Node 24.19.0. The final generation's wall-time scheduling tests also require `TZ=America/New_York`; without it, a UTC container shifts the expected timer boundary by four hours. No source or script difference is needed.

## Checked boundaries

The matrix establishes these results:

- Final backend plus old frontend is supported: the browser uses legacy `/active`, which exposes only deliberate All-pages banners.
- Final backend plus final frontend is supported: the browser uses `/active/v2`, receives typed targets, and renders only All-pages plus the matching `/login` target in priority order.
- Old backend plus final frontend fails closed: old Gateway returns HTTP 401 for `/active/v2`; the frontend does not fall back to legacy and renders no banner region.
- Old backend plus old frontend is unsafe while targeted rows remain: legacy `/active` exposes a targeted row and the old frontend renders it site-wide on `/not-login`.
- The supported rollback sequence freezes banner-management writes, rolls back the frontend, disables every Active or Scheduled targeted occurrence, verifies the boundary, rolls back Operations and Gateway, and keeps management writes frozen below the targeting-capable backend. The deliberate All-pages banner remains available. A premature backend boundary crossing is rejected by the proof.

Ticket 18 proves ordering, not the deployment-specific traffic-control mechanism. Tickets 20 and 21 own that mechanism.

## Reproducibility and scope

The proof binds these inputs:

- backend commits/trees: old `9251d64f607acc198d95c7d53294807cc56efa82` / `d6195f4acced760904d1e0d025dc86c4983fa64f`; final `9c17b0caecbee1b7f2231ca974b8b8b59ba7f211` / `c211efbbe69944c791b2d7f897b9d05b1593e71d`
- frontend commits/trees: old `e49ae2d07cfb76cdbe9186161c3d726ae76ba416` / `419ef5cf7ff8f9981218976e93a14f51ea17b8f2`; final `7b69aa960ff98f97c1a2d026b7137b0e3dcdf603` / `e4506d9e5bca3a42da2e5436750c8951da2076ee`
- frontend Dockerfile SHA-256 `23a550f373f07475efd8a838161e5e031e8706b14640a8a13d44de9ef0c9938e` and lockfile SHA-256 `47fe7fcc0c0d775ad771ceca0f28327d019d2816639e88699eeae62256a2d2bc`
- generated `.env` SHA-256 `6c9fbb1069a9b8d17e417c68a1bbdc63975bd2a8ae6fade26ff90bf04c90df30` and HTTP vhost SHA-256 `ccc5da4924de7ef2b698f4eacc0798588de16394fe57cd1aab512c2df5275ed1`
- digest-pinned Java 25 build/runtime, Node 24.19.0 frontend builder, httpd 2.4.68 frontend runtime, MySQL 8.0.43, Flyway 11.7.2, and Chromium/Playwright 1.60.0 images recorded in `provenance.json`
- Ticket 17 matrix SHA-256 `a211596a81df2488caad8a9ffefe881aff9804fda7a6199e3968cbdf1535614d`

All banners and credentials are synthetic and disposable. This is an HTTP-only local proof; it intentionally does not claim TLS, Jenkins, AWS/RDS, PSAMA, release-control, traffic-controller, or deployment-engine parity. It performs no live mutation and contains no patient data or secrets. No frontend production source is changed by this ticket.
