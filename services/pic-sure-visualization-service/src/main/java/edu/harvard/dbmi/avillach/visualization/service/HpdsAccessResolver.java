package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.BadVisualizationRequestException;
import edu.harvard.dbmi.avillach.visualization.error.VisualizationConfigurationException;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.HpdsAccessContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class HpdsAccessResolver {

    private final UUID authorizedResourceUUID;
    private final UUID openResourceUUID;

    public HpdsAccessResolver(
        @Value("${hpds.resource.authorized.uuid:}") String authorizedUUID, @Value("${hpds.resource.open.uuid:}") String openUUID
    ) {
        this.authorizedResourceUUID = parseRequiredUUID(authorizedUUID, "hpds.resource.authorized.uuid");
        this.openResourceUUID = parseConfiguredUUID(openUUID);
    }

    public HpdsAccessContext resolve(UUID hpdsResourceUUID) {
        if (hpdsResourceUUID == null) {
            throw new BadVisualizationRequestException("Request must contain an 'hpdsResourceUUID' field");
        }
        if (hpdsResourceUUID.equals(authorizedResourceUUID)) {
            return new HpdsAccessContext(hpdsResourceUUID, AccessType.AUTHORIZED);
        }
        if (hpdsResourceUUID.equals(openResourceUUID)) {
            return new HpdsAccessContext(hpdsResourceUUID, AccessType.OPEN);
        }
        if (openResourceUUID == null) {
            throw new VisualizationConfigurationException("Open HPDS resource UUID (hpds.resource.open.uuid) is not configured");
        }
        throw new BadVisualizationRequestException("Unsupported HPDS resource UUID: " + hpdsResourceUUID);
    }

    private static UUID parseRequiredUUID(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new VisualizationConfigurationException("Required HPDS resource UUID (" + propertyName + ") is not configured");
        }
        return UUID.fromString(value);
    }

    private static UUID parseConfiguredUUID(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
