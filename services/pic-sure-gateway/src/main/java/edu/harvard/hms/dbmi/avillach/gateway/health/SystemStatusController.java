package edu.harvard.hms.dbmi.avillach.gateway.health;

/**
 * Backward-compatible replacement for the WAR's SystemService {@code /system/status}. Plain-text, unauthenticated (on the introspection
 * allow-list), throttled to one real deep check per window with a cached last result to avoid a DoS via the deep probes.
 *
 * <p> Deliberately <em>not</em> a {@code @RestController}/{@code @GetMapping}: the gateway's route-backed {@code RouterFunction} beans are
 * registered via {@code RouterFunctionMapping}, which Spring wires at order -1 -- ahead of {@code RequestMappingHandlerMapping} (order 0).
 * An annotated controller mapping here would be silently overridden by any same-path {@code RouterFunction}. See
 * {@link edu.harvard.hms.dbmi.avillach.gateway.config.HealthConfig} for the higher-precedence {@code RouterFunction} that actually exposes
 * this at {@code GET /system/status}. (Actuator's own endpoints are unaffected: {@code WebMvcEndpointHandlerMapping} is order -100, ahead
 * of {@code RouterFunctionMapping}.)
 */
public class SystemStatusController {

    static final String RUNNING = "RUNNING";
    static final String DEGRADED = "ONE OR MORE COMPONENTS DEGRADED";

    private final SystemHealthService health;
    private final long windowMs;

    private volatile String lastStatus = "UNTESTED";
    private volatile long lastCheck = 0L;

    public SystemStatusController(SystemHealthService health, long windowMs) {
        this.health = health;
        this.windowMs = windowMs;
    }

    public String status() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < windowMs && lastCheck != 0L) {
            return lastStatus;
        }
        synchronized (this) {
            if (now - lastCheck < windowMs && lastCheck != 0L) {
                return lastStatus;
            }
            lastCheck = now;
            try {
                lastStatus = health.check().running() ? RUNNING : DEGRADED;
            } catch (Exception e) {
                lastStatus = DEGRADED;
            }
            return lastStatus;
        }
    }

    /** Test hook: reset the throttle window so the next call re-checks. */
    void expireCacheForTest() {
        this.lastCheck = 0L;
    }
}
