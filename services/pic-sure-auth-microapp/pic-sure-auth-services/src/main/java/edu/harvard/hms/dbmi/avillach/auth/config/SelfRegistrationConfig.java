package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves and validates the connection new self-registered users are attached to.
 *
 * <p>The connection id comes from {@code SELF_REGISTRATION_CONNECTION_ID}. When it is set, it must
 * match an existing {@link Connection}, checked eagerly here so a typo'd id fails application
 * startup instead of surfacing later as a 500 on the first registration attempt. When it is unset,
 * self-registration is simply disabled.</p>
 */
@Component
public class SelfRegistrationConfig {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final ConnectionRepository connectionRepository;
    private final String connectionId;

    private Connection connection;

    @Autowired
    public SelfRegistrationConfig(
        ConnectionRepository connectionRepository, @Value("${application.self-registration.connection-id}") String connectionId
    ) {
        this.connectionRepository = connectionRepository;
        this.connectionId = connectionId;
    }

    @PostConstruct
    public void init() {
        if (StringUtils.isBlank(connectionId)) {
            logger.info("Self-registration is disabled (SELF_REGISTRATION_CONNECTION_ID is not set).");
            return;
        }

        this.connection = connectionRepository.findById(connectionId)
            .orElseThrow(
                () -> new IllegalStateException(
                    "SELF_REGISTRATION_CONNECTION_ID=" + connectionId + " does not match any known connection."
                )
            );
        logger.info("Self-registration is enabled, connection id: {}", connectionId);
    }

    public boolean isEnabled() {
        return connection != null;
    }

    public Connection getConnection() {
        return connection;
    }
}
