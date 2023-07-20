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
import java.util.Optional;
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
    private int targetPicsureObfuscationThreshold;
    private int targetPicsureObfuscationVariance;
    private String targetPicsureObfuscationSalt;

    public static final int DEFAULT_OBFUSCATION_THRESHOLD = 10;
    public static final int DEFAULT_OBFUSCATION_VARIANCE = 3;

    public int getTargetPicsureObfuscationThreshold() {
        return targetPicsureObfuscationThreshold;
    }

    public void setTargetPicsureObfuscationThreshold(int targetPicsureObfuscationThreshold) {
        this.targetPicsureObfuscationThreshold = targetPicsureObfuscationThreshold;
    }

    public int getTargetPicsureObfuscationVariance() {
        return targetPicsureObfuscationVariance;
    }

    public void setTargetPicsureObfuscationVariance(int targetPicsureObfuscationVariance) {
        this.targetPicsureObfuscationVariance = targetPicsureObfuscationVariance;
    }

    public String getTargetPicsureObfuscationSalt() {
        return targetPicsureObfuscationSalt;
    }

    public void setTargetPicsureObfuscationSalt(String targetPicsureObfuscationSalt) {
        this.targetPicsureObfuscationSalt = targetPicsureObfuscationSalt;
    }

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

        targetPicsureObfuscationThreshold = Optional.ofNullable(properties.getProperty("target.picsure.obfuscation_threshold"))
                .map(Integer::parseInt)
                .orElse(DEFAULT_OBFUSCATION_THRESHOLD);

        targetPicsureObfuscationVariance = Optional.ofNullable(properties.getProperty("target.picsure.obfuscation_variance"))
                .map(Integer::parseInt)
                .orElse(DEFAULT_OBFUSCATION_VARIANCE);

        targetPicsureObfuscationSalt = Optional.ofNullable(properties.getProperty("target.picsure.obfuscation_salt"))
                .orElseGet(() -> UUID.randomUUID().toString());
    }
}