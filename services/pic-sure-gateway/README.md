# pic-sure-gateway

Spring Cloud Gateway MVC (servlet) front door for PIC-SURE. **Phase 1: transparent pass-through** — a single low-priority catch-all route (`Path=/**`, order 1000) forwards every request to the legacy WildFly with the `/pic-sure-api-2/PICSURE/` prefix re-applied. No auth, no audit, no behavior change; the gateway only adds observability (Actuator, Prometheus, structured JSON logs, `X-Request-Id` propagation).

## Build & test

```bash
# from the repo root (JDK 25 — enforced)
mvn -pl services/pic-sure-gateway -am verify
```

The WireMock test (`CatchAllRouteTest`) doubles as the binding check for the Spring Cloud 2025.0.x property prefix `spring.cloud.gateway.server.webmvc.routes`.

## Run

```bash
# local jar (WildFly reachable on localhost)
WILDFLY_URL=http://localhost:8080 java -jar target/pic-sure-gateway-*.jar --spring.profiles.active=local

# container on the AIO `picsure` network
cp env.example .env   # adjust if needed
docker compose up --build
```

Config: `WILDFLY_URL` (catch-all target), `SPRING_PROFILE` (`aio` default | `local`).
Observability: `/actuator/health`, `/actuator/prometheus`, `/actuator/metrics`; every response carries `X-Request-Id` (generated if absent) and logs carry it as MDC `requestId`.

## Phase-1 rollback

The gateway is inserted by re-pointing Apache httpd's `/picsure/*` rules at it (both the `:80` `[P,L]` rule and the `:443` `[P]` rule in `pic-sure-all-in-one/initial-configuration/config/httpd/httpd-vhosts.conf`). **Rollback = revert those httpd rules to `http://wildfly:8080/pic-sure-api-2/PICSURE/$1`** — the gateway holds no state and owns no auth in this phase, so reverting httpd restores the original path entirely.
