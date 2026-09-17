package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.service.IdpProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the {@link IdpProvisioningService} (if any) that applies to a given {@link Connection}.
 * Mirrors {@code AuthenticationServiceRegistry}'s pattern of Spring-collecting every bean implementing
 * the interface, but keyed by a predicate ({@link IdpProvisioningService#supports(Connection)}) instead
 * of a provider-name map, since there's no shared provider identifier on {@code Connection} to key off.
 */
@Component
public class IdpProvisioningRegistry {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final List<IdpProvisioningService> idpProvisioningServices;

    @Autowired
    public IdpProvisioningRegistry(List<IdpProvisioningService> idpProvisioningServices) {
        this.idpProvisioningServices = idpProvisioningServices;
        logger.info("Registered {} IdP provisioning service(s).", idpProvisioningServices.size());
    }

    public Optional<IdpProvisioningService> findFor(Connection connection) {
        if (connection == null) {
            return Optional.empty();
        }

        return idpProvisioningServices.stream().filter(service -> service.supports(connection)).findFirst();
    }
}
