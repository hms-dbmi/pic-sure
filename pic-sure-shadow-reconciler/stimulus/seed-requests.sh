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

# Placeholder resource id for path-parameterized routes (search). Valid-UUID-shaped so JAX-RS path binding accepts it;
# the introspection decision is path+token based (evaluated by JWTFilter BEFORE the resource is looked up), not
# resource-existence based -- so a placeholder id still yields a real allow/reject decision.
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

echo "== /query/ : allow + reject (cosmetic /picsure + decision-affecting /v3 variants both canonicalize to /query/sync) =="
post "$VALID_TOKEN"   "/picsure/query/sync" '{"resourceUUID":"R","query":{"fields":[]}}'   # allow-shaped
post "$VALID_TOKEN"   "/v3/query/sync"      '{"resourceUUID":"R","query":{"fields":[]}}'   # allow-shaped (/v3 variant)
post "$NOPRIV_TOKEN"  "/picsure/query/sync" '{"resourceUUID":"R","query":{"fields":[]}}'   # reject-shaped
post "$EXPIRED_TOKEN" "/picsure/query/sync" '{"resourceUUID":"R","query":{"fields":[]}}'   # reject-shaped
post "$NOPRIV_TOKEN"  "/v3/query/sync"      '{"resourceUUID":"R","query":{"fields":[]}}'   # reject-shaped (/v3 variant)
cover "/query/sync"

echo "== /search/ : allow + reject =="
post "$VALID_TOKEN"   "/picsure/search/$RESOURCE_ID" '{"query":"searchTerm"}'   # allow-shaped
post "$NOPRIV_TOKEN"  "/picsure/search/$RESOURCE_ID" '{"query":"searchTerm"}'   # reject-shaped
post "$EXPIRED_TOKEN" "/picsure/search/$RESOURCE_ID" '{"query":"searchTerm"}'   # reject-shaped
cover "/search/$RESOURCE_ID"

# Any reference-mapping route that genuinely cannot be exercised with the 3 tokens alone (e.g. one needing a real,
# pre-existing resource/query id) must be declared here with skip_route "<canonical route>" "<reason>" rather than
# omitted silently. As of this mapping, /query/ and /search/ are both auth-path exercisable, so none are skipped.

echo "== open-access allow/deny (no token; canonicalizes to /query/sync, already covered) =="
post "" "/picsure/query/sync" '{"query":{"fields":["allowed_concept"]}}'
post "" "/picsure/query/sync" '{"query":{"fields":["restricted_concept"]}}'

echo
echo "Canonical routes covered (pass to the reconciler as --routes $ROUTES_FILE):"
grep -v '^#' "$ROUTES_FILE"
