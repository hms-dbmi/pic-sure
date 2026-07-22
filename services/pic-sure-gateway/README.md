# pic-sure-gateway

Spring Cloud Gateway MVC (servlet) front door for PIC-SURE. Static, per-service routes forward to `logging`, `dictionary`,
`visualization`, `hpds` (query-service), `configuration`, and `dataset` (operations-service) — see `application.yml` for the full route
table. The gateway owns query-read authorization end to end: it authenticates via PSAMA introspection, audits requests, and buffers/dispatches
as needed. There is no catch-all route; any path that doesn't match a configured route 404s.

## Build & test

```bash
# from the repo root (JDK 25 — enforced)
mvn -pl services/pic-sure-gateway -am verify
```

`NoRegistryRouteTest` and `RouteOwnedPrefixDriftTest` bind `spring.cloud.gateway.server.webmvc.routes` straight from the Spring
environment (Spring Cloud 2025.0.x property prefix) and assert every configured route is gateway-owned, catching route-table drift at
build time.

## Run

```bash
# local jar
java -jar target/pic-sure-gateway-*.jar --spring.profiles.active=local

# container on the AIO `picsure` network
cp env.example .env   # adjust if needed
docker compose up --build
```

Config: `HPDS_QUERY_SERVICE_URL`, `OPERATIONS_SERVICE_URL`, `TOKEN_INTROSPECTION_URL` (PSAMA), `SPRING_PROFILE` (`aio` default | `local`).
Observability: `/actuator/health`, `/actuator/prometheus`, `/actuator/metrics` (off by default; enabled via `PICSURE_ACTUATOR_EXPOSURE`);
every response carries `X-Request-Id` (generated if absent) and logs carry it as MDC `requestId`.
