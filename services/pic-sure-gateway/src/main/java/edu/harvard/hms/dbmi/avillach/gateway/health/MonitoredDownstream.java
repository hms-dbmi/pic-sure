package edu.harvard.hms.dbmi.avillach.gateway.health;

/**
 * One monitored downstream (spec 3.8).
 *
 * @param name component name surfaced in /actuator/health and logs
 * @param baseUrl base URL (reuse the routing env-var URL)
 * @param healthPath path appended to baseUrl; default "/actuator/health", overridable (logging -> "/health"; an interim non-Actuator
 *        service -> "/info")
 * @param method HTTP method for the probe ("GET" or "POST"); default GET
 * @param requestBody body sent for POST probes (e.g. "{}" for a PSAMA introspection fallback)
 * @param sendAppToken whether to send the application/service bearer on the probe (Phase 6 detail)
 * @param successStatus expected HTTP status for "up" (default 200)
 * @param requireStatusUp if true, also require an Actuator body of {"status":"UP"}
 */
public record MonitoredDownstream(
    String name, String baseUrl, String healthPath, String method, String requestBody, boolean sendAppToken, Integer successStatus,
    boolean requireStatusUp
) {
    public MonitoredDownstream {
        if (healthPath == null || healthPath.isBlank()) {
            healthPath = "/actuator/health";
        }
        if (method == null || method.isBlank()) {
            method = "GET";
        }
        if (successStatus == null) {
            successStatus = 200;
        }
    }

    public String resolvedUrl() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = healthPath.startsWith("/") ? healthPath : "/" + healthPath;
        return base + path;
    }
}
