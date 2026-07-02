package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Path-aware Phase 2↔4 interim (decision 11, P-M2), mapped to the spec's Option-A flags. The query-read
 * paths (result/signed-url) are owned by WildFly while GATEWAY_OWNS_QUERY_READ_AUTH is false (Phase 2) and
 * by the gateway once it flips to true (Phase 4 — QueryAuthFetcher goes live). Replaces the all-or-nothing
 * single flag. The overall GATEWAY_OWNS_AUTH gate lives on the WildFly side (GatewayAuthDelegation, Task 17).
 */
public class GatewayAuthScope {

    private final boolean gatewayOwnsQueryReadAuth;
    private final List<Pattern> queryReadPaths;

    public GatewayAuthScope(boolean gatewayOwnsQueryReadAuth, List<String> queryReadPathPatterns) {
        this.gatewayOwnsQueryReadAuth = gatewayOwnsQueryReadAuth;
        this.queryReadPaths = queryReadPathPatterns == null ? List.of()
            : queryReadPathPatterns.stream().map(Pattern::compile).toList();
    }

    /** True when WildFly still owns auth/audit for this path (gateway filters must NOT run): a query-read
     *  path while GATEWAY_OWNS_QUERY_READ_AUTH is false. */
    public boolean interimOwnedByWildFly(String path) {
        if (gatewayOwnsQueryReadAuth || path == null) {
            return false;
        }
        for (Pattern p : queryReadPaths) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Inverse: the gateway owns auth/audit for this path now. */
    public boolean gatewayOwnsAuth(String path) {
        return !interimOwnedByWildFly(path);
    }
}
