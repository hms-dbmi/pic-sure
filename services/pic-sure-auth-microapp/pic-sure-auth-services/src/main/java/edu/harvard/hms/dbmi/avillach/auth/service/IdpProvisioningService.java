package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;

/**
 * <p>Provisions/deprovisions a user's account with an identity provider, triggered when an admin
 * approves or deactivates a pic-sure user (see {@code UserService.updateUser}).</p>
 *
 * <p>Implementations are each scoped to a specific {@link Connection} (via their own config, checked in
 * {@link #supports(Connection)}) rather than a shared provider-name key — there's no canonical provider
 * taxonomy on {@code Connection} today, unlike the login-side {@code AuthenticationService}, which is
 * resolved by an explicit provider the frontend already knows out-of-band. Resolved via {@code
 * IdpProvisioningRegistry}; a connection with no matching implementation simply has no provisioning
 * step, which is the expected case for most connections in a deployment.</p>
 */
public interface IdpProvisioningService {

    boolean supports(Connection connection);

    void provisionUser(User user);

    void deprovisionUser(User user);

    boolean isDeprovisioningEnabled();
}
