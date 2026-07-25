package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.BadVisualizationRequestException;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.HpdsAccessContext;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Decides AUTHORIZED vs OPEN treatment for a distributions request from the gateway-owned {@link GatewayUserResolver#HEADER_ACCESS_TYPE}
 * header. The gateway sets it from whichever auth filter admitted the request and strips any client-supplied value on the way in, so
 * clients cannot spoof it.
 *
 * <p>This replaces two earlier signals, both of which were wrong. The removed resource registry's {@code hpdsResourceUUID} body field was
 * client-supplied. Its replacement -- "any non-blank {@code X-User-Id} means authorized" -- misclassified every open-access request,
 * because the gateway sets {@code X-User-Id} to the marker {@code OPEN_ACCESS:<host>} on the open path, and that marker is non-blank.
 *
 * <p>Fails closed. A missing or unrecognized header means the request did not traverse the gateway auth chain, so there is no trustworthy
 * access type to act on: defaulting to AUTHORIZED would expose non-obfuscated counts, and defaulting to OPEN would silently downgrade
 * legitimate users to obfuscated counts with nothing in the response to say so.
 */
@Component
public class AccessTypeResolver {

    public HpdsAccessContext resolve(String accessTypeHeader) {
        if (accessTypeHeader != null) {
            String normalized = accessTypeHeader.trim().toLowerCase(Locale.ROOT);
            if (GatewayUserResolver.ACCESS_TYPE_AUTHORIZED.equals(normalized)) {
                return new HpdsAccessContext(null, AccessType.AUTHORIZED);
            }
            if (GatewayUserResolver.ACCESS_TYPE_OPEN.equals(normalized)) {
                return new HpdsAccessContext(null, AccessType.OPEN);
            }
        }
        throw new BadVisualizationRequestException("Missing or unrecognized X-Picsure-Access-Type header");
    }
}
