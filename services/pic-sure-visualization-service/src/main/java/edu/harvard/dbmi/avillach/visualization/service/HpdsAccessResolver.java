package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.HpdsAccessContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Decides AUTHORIZED vs OPEN treatment for a distributions request. The access type comes from the gateway's identity propagation, NOT from
 * the request body: the {@code X-User-Id} header is gateway-owned (the gateway strips any client-supplied value and only injects it after
 * successful PSAMA introspection), so its presence proves an authenticated caller and its absence means the request passed open-access
 * validation instead. Clients cannot spoof it, unlike the legacy {@code hpdsResourceUUID} body field, which was the removed resource
 * registry's backend selector.
 *
 * <p>{@code hpdsResourceUUID} is still accepted for compatibility and passed through into HPDS request bodies (HPDS ignores the field);
 * when the body omits it, the optionally-configured per-access-type UUID is used, and {@code null} is fine too.
 */
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

    public HpdsAccessContext resolve(String gatewayUserId, UUID hpdsResourceUUID) {
        boolean authorized = gatewayUserId != null && !gatewayUserId.isBlank();
        UUID resourceUUID = hpdsResourceUUID != null ? hpdsResourceUUID : (authorized ? authorizedResourceUUID : openResourceUUID);
        return new HpdsAccessContext(resourceUUID, authorized ? AccessType.AUTHORIZED : AccessType.OPEN);
    }

    private static UUID parseConfiguredUUID(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
