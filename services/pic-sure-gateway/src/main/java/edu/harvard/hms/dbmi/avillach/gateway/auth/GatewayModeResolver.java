package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single source of truth for the gateway's effective auth mode and the per-request enforce decision.
 *
 * <p><b>Resolution</b> (see {@link #resolve}): if {@code picsure.gateway.security.mode} is explicitly set, that mode wins; otherwise the
 * legacy {@code picsure.gateway.security.auth-enabled} boolean drives it ({@code true} → {@link GatewayAuthMode#ENFORCE}, {@code false} →
 * {@link GatewayAuthMode#TRANSPARENT}). So today's production topology ({@code auth-enabled=true}, mode unset) resolves to ENFORCE and
 * keeps working unchanged. The resolved mode plus the {@code (auth-enabled, mode)} tuple are logged prominently at startup (see
 * {@link #create}); an UNUSUAL tuple ({@code auth-enabled=false} with {@code mode=enforce}) logs a WARN spelling out what will actually
 * happen.
 *
 * <p><b>Per-request decision.</b> ENFORCE enforces every route. TRANSPARENT registers no auth filters at all.
 */
public final class GatewayModeResolver {

    private static final Logger log = LoggerFactory.getLogger(GatewayModeResolver.class);

    private final GatewayAuthMode effectiveMode;

    public GatewayModeResolver(GatewayAuthMode effectiveMode) {
        this.effectiveMode = Objects.requireNonNull(effectiveMode, "effectiveMode");
    }

    /**
     * Resolves the effective mode. An explicit {@code mode} always wins; when unset, {@code auth-enabled} decides (true → ENFORCE, false →
     * TRANSPARENT). Pure function — the single place the {@code (auth-enabled, mode)} tuple becomes one effective mode.
     */
    public static GatewayAuthMode resolve(GatewayAuthMode explicitMode, boolean authEnabled) {
        if (explicitMode != null) {
            return explicitMode;
        }
        return authEnabled ? GatewayAuthMode.ENFORCE : GatewayAuthMode.TRANSPARENT;
    }

    /** An UNUSUAL tuple is one where an explicit mode overrides what auth-enabled alone would imply in a security-relevant direction. */
    static boolean isUnusualTuple(GatewayAuthMode explicitMode, boolean authEnabled) {
        return !authEnabled && explicitMode == GatewayAuthMode.ENFORCE;
    }

    /** Resolves the effective mode, logs it prominently (WARN on an unusual tuple), and returns the wired resolver. */
    public static GatewayModeResolver create(GatewayAuthMode explicitMode, boolean authEnabled) {
        GatewayAuthMode effective = resolve(explicitMode, authEnabled);
        logResolution(explicitMode, authEnabled, effective);
        return new GatewayModeResolver(effective);
    }

    static void logResolution(GatewayAuthMode explicitMode, boolean authEnabled, GatewayAuthMode effective) {
        String tuple = "auth-enabled=" + authEnabled + ", mode=" + (explicitMode == null ? "<unset>" : explicitMode.name().toLowerCase());
        if (isUnusualTuple(explicitMode, authEnabled)) {
            log.warn(
                "Gateway auth mode resolved to {} from an UNUSUAL config tuple ({}). {}", effective, tuple,
                unusualExplanation(explicitMode, authEnabled)
            );
        } else {
            log.info("Gateway auth mode resolved to {} ({}). {}", effective, tuple, behaviorSummary(effective));
        }
    }

    private static String unusualExplanation(GatewayAuthMode explicitMode, boolean authEnabled) {
        // authEnabled == false && explicitMode == ENFORCE
        return "mode=enforce overrides auth-enabled=false: the gateway WILL fully enforce on every route (all auth filters active) "
            + "despite auth-enabled=false.";
    }

    private static String behaviorSummary(GatewayAuthMode effective) {
        return switch (effective) {
            case ENFORCE -> "Full gateway enforcement on every route.";
            case TRANSPARENT -> "Pure pass-through; no gateway auth filters active.";
        };
    }

    public GatewayAuthMode effectiveMode() {
        return effectiveMode;
    }

    /**
     * True iff this request must get the full enforce treatment. These requests buffer, introspect/validate, mutate, propagate identity,
     * and audit exactly as production enforce does.
     */
    public boolean enforcesFor(String path) {
        return effectiveMode == GatewayAuthMode.ENFORCE;
    }

    /** An ENFORCE resolver — for tests and non-Spring callers. */
    public static GatewayModeResolver enforcing() {
        return new GatewayModeResolver(GatewayAuthMode.ENFORCE);
    }
}
