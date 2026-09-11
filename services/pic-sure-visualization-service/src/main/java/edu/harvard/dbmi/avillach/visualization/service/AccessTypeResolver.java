package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.BadVisualizationRequestException;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import org.springframework.stereotype.Component;

/**
 * Resolves the HPDS backend from the explicit visualization path segment. Gateway authentication metadata does not select a backend because
 * authenticated callers may use the open path.
 */
@Component
public class AccessTypeResolver {

    public AccessType resolve(String backend) {
        if ("auth".equals(backend)) {
            return AccessType.AUTHORIZED;
        }
        if ("open".equals(backend)) {
            return AccessType.OPEN;
        }
        throw new BadVisualizationRequestException("Missing or unrecognized visualization backend");
    }
}
