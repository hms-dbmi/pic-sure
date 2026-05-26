package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
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
        this.authorizedResourceUUID = parseConfiguredUUID(authorizedUUID);
        this.openResourceUUID = parseConfiguredUUID(openUUID);
    }

    public HpdsAccessContext resolve(UUID hpdsResourceUUID) {
        if (hpdsResourceUUID == null) {
            throw new VisualizationException("Request must contain an 'hpdsResourceUUID' field");
        }
        if (hpdsResourceUUID.equals(authorizedResourceUUID)) {
            return new HpdsAccessContext(hpdsResourceUUID, AccessType.AUTHORIZED);
        }
        if (hpdsResourceUUID.equals(openResourceUUID)) {
            return new HpdsAccessContext(hpdsResourceUUID, AccessType.OPEN);
        }
        throw new VisualizationException("Unsupported HPDS resource UUID: " + hpdsResourceUUID);
    }

    private static UUID parseConfiguredUUID(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
