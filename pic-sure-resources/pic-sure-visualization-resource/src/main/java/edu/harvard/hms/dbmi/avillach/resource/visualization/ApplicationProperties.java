package edu.harvard.hms.dbmi.avillach.resource.visualization;

import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.PicsureQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

@ApplicationScoped
public class ApplicationProperties implements Serializable {

    private final Logger logger = LoggerFactory.getLogger(ApplicationProperties.class);

    private String contextPath;
    private UUID visualizationResourceId;
    private UUID authHpdsResourceId;
    private UUID openHpdsResourceId;
    private String origin;

    public String getContextPath() {
        return contextPath;
    }

    public UUID getVisualizationResourceId() {
        return visualizationResourceId;
    }

    public UUID getAuthHpdsResourceId() {
        return authHpdsResourceId;
    }

    public UUID getOpenHpdsResourceId() {
        return openHpdsResourceId;
    }

    public String getOrigin() { return origin; }

    public void init(String contextPath) {
        this.contextPath = contextPath;

        Path configFile = Path.of(System.getProperty("jboss.server.config.dir"), "visualization", contextPath,
                "resource.properties");
        logger.debug("Loading resource properties file: " + configFile);
        Properties properties;
        try {
            properties = new Properties();
            properties.load(Files.newInputStream(configFile));
        } catch (IOException e) {
            throw new ApplicationException("Error while reading resource properties file: " + configFile, e);
        }

        origin = properties.getProperty("target.origin.id");
        logger.debug("origin: " + origin);
        if (origin == null)
            throw new PicsureQueryException("origin property must be set.");

        visualizationResourceId = UUID.fromString(properties.getProperty("visualization.resource.id"));
        logger.debug("visualizationResourceId: " + visualizationResourceId);
        if (visualizationResourceId == null)
            throw new PicsureQueryException("visualization.resource.id property must be set.");

        authHpdsResourceId = UUID.fromString(properties.getProperty("auth.hpds.resource.id"));
        if (authHpdsResourceId == null)
            throw new PicsureQueryException("auth.hpds.resource.id property must be set.");

        openHpdsResourceId = UUID.fromString(properties.getProperty("open.hpds.resource.id"));
        if (openHpdsResourceId == null)
            throw new PicsureQueryException("open.hpds.resource.id property must be set.");
    }
}