#!/usr/bin/env bash
# Drives an allow-shaped + reject-shaped request through the observe-mode gateway for EVERY canonical route in the
# reconciler's reference mapping (pic-sure-shadow-reconciler/src/main/resources/target-service-mapping.yml), so the
# reconciler sees both a WildFly allow and a WildFly reject decision per route -- not just for /query/.
#
# 3-token matrix (unchanged convention):
#   VALID_TOKEN   -> allow-shaped   (privileged user; PSAMA introspection returns active)
#   NOPRIV_TOKEN  -> reject-shaped  (authenticated, no privileges; PSAMA returns inactive)
#   EXPIRED_TOKEN -> reject-shaped  (expired/revoked session; auth failure -> inactive)
#
# The script also WRITES the canonical routes it covered to ROUTES_FILE (default <script dir>/canonical-routes.txt) so
# the reconciler's exit gate can be run with `--routes <that file>` and the two share ONE source of truth for the route
# universe. Routes that cannot be exercised without live data are printed as "SKIPPED <route> - <reason>" and left OUT
# of the routes file (so the gate does not demand coverage the seed cannot produce).
set -euo pipefail
: "${GATEWAY_URL:?}"; : "${VALID_TOKEN:?}"; : "${NOPRIV_TOKEN:?}"; : "${EXPIRED_TOKEN:?}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROUTES_FILE="${ROUTES_FILE:-$SCRIPT_DIR/canonical-routes.txt}"

# Placeholder resource id, used BOTH as the search path parameter AND as the /query/* body resourceUUID. It MUST be
# valid-UUID-shaped: WildFly's JWTFilter.prepareRequestMap() calls UUID.fromString() on the body's resourceUUID for
# /query/* (non-v3) paths BEFORE introspection runs -- a non-UUID value throws IllegalArgumentException there, the WAR
# 500s, ShadowLog never fires, and the request lands as UNPAIRED (verified live 2026-07-05). A valid-but-nonexistent
# UUID is fine: the resource lookup returns null, the queryFormat enrichment is skipped, and introspection proceeds,
# so the decision is still path+token based, not resource-existence based.
RESOURCE_ID="00000000-0000-0000-0000-000000000000"

# Rewrite the covered-routes file from scratch each run, prefixed with a provenance header.
{
  echo "# Canonical routes covered by seed-requests.sh (generated $(date -u +%Y-%m-%dT%H:%M:%SZ))."
  echo "# One canonical route per line; pass to the reconciler as: --routes $ROUTES_FILE"
} > "$ROUTES_FILE"

post() { # $1=token $2=path $3=body
  curl -sS -o /dev/null -w "%{http_code} $2\n" \
    -X POST "$GATEWAY_URL$2" \
    -H "Authorization: Bearer $1" -H "Content-Type: application/json" -d "$3"
}

cover() { # $1=canonical route just exercised with both allow- and reject-shaped requests
  echo "$1" >> "$ROUTES_FILE"
  echo "COVERED $1"
}

skip_route() { # $1=canonical route $2=reason -- explicit, never silent
  echo "SKIPPED $1 - $2"
}

# GATEWAY_URL is the INGRESS origin (httpd), per the runbook. httpd strips the leading /picsure/ before proxying to
# the gateway (RewriteRule ^/picsure/(.*)$ http://gateway:8080/$1), so the raw paths PIC-SURE actually sees are
# /query/sync (identity) and /v3/query/sync (decision-affecting v3 variant). The mapping's /picsure/* cosmetic rules
# are not reachable through this ingress (the prefix never survives httpd) -- they remain in the mapping for
# deployments whose ingress forwards the prefix verbatim.
echo "== /query/ : allow + reject (identity + decision-affecting /v3 variants both canonicalize to /query/sync) =="
post "$VALID_TOKEN"   "/picsure/query/sync"    "{\"resourceUUID\":\"$RESOURCE_ID\",\"query\":{\"fields\":[]}}"   # allow-shaped
post "$VALID_TOKEN"   "/picsure/v3/query/sync" "{\"resourceUUID\":\"$RESOURCE_ID\",\"query\":{\"fields\":[]}}"   # allow-shaped (/v3 variant)
post "$NOPRIV_TOKEN"  "/picsure/query/sync"    "{\"resourceUUID\":\"$RESOURCE_ID\",\"query\":{\"fields\":[]}}"   # reject-shaped
post "$EXPIRED_TOKEN" "/picsure/query/sync"    "{\"resourceUUID\":\"$RESOURCE_ID\",\"query\":{\"fields\":[]}}"   # reject-shaped
post "$NOPRIV_TOKEN"  "/picsure/v3/query/sync" "{\"resourceUUID\":\"$RESOURCE_ID\",\"query\":{\"fields\":[]}}"   # reject-shaped (/v3 variant)
cover "/query/sync"

echo "== /search/ : allow + reject =="
post "$VALID_TOKEN"   "/picsure/search/$RESOURCE_ID" '{"query":"searchTerm"}'   # allow-shaped
post "$NOPRIV_TOKEN"  "/picsure/search/$RESOURCE_ID" '{"query":"searchTerm"}'   # reject-shaped
post "$EXPIRED_TOKEN" "/picsure/search/$RESOURCE_ID" '{"query":"searchTerm"}'   # reject-shaped
cover "/search/$RESOURCE_ID"

# Any reference-mapping route that genuinely cannot be exercised with the 3 tokens alone (e.g. one needing a real,
# pre-existing resource/query id) must be declared here with skip_route "<canonical route>" "<reason>" rather than
# omitted silently. As of this mapping, /query/ and /search/ are both auth-path exercisable, so none are skipped.

# Open-access posts only measure something when the deployment has open access ENABLED (gateway
# GATEWAY_OPEN_ACCESS_ENABLED / WildFly openAccessEnabled). Against a deployment with it disabled, a token-less
# request just 401s on the introspection path WITHOUT a WildFly open-access decision to pair -- producing UNPAIRED
# noise that (correctly) fails the exit gate. So they are opt-in.
if [ "${OPEN_ACCESS:-false}" = "true" ]; then
  echo "== open-access allow/deny (no token; canonicalizes to /query/sync, already covered) =="
  post "" "/picsure/query/sync" '{"query":{"fields":["allowed_concept"]}}'
  post "" "/picsure/query/sync" '{"query":{"fields":["restricted_concept"]}}'
else
  echo "SKIPPED open-access posts - OPEN_ACCESS!=true (feature disabled in this deployment; nothing to pair)"
fi

echo
echo "Canonical routes covered (pass to the reconciler as --routes $ROUTES_FILE):"
grep -v '^#' "$ROUTES_FILE"
