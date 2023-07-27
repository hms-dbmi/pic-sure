package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.PicsureQueryException;

@ApplicationScoped
public class ApplicationProperties implements Serializable {

    private String contextPath;
    private String targetPicsureUrl;
    private String targetResourceId;
    private String targetPicsureToken;
    private int targetPicsureObfuscationThreshold;
    private int targetPicsureObfuscationVariance;
    private String targetPicsureObfuscationSalt;
    private UUID visualizationResourceId;

    public static final int DEFAULT_OBFUSCATION_THRESHOLD = 10;
    public static final int DEFAULT_OBFUSCATION_VARIANCE = 3;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    public String getContextPath() {
        return contextPath;
    }

    public String getTargetPicsureUrl() {
        return targetPicsureUrl;
    }
    
    public String getTargetResourceId() {
		return targetResourceId;
	}

    public String getTargetPicsureToken() {
        return targetPicsureToken;
    }

    public int getTargetPicsureObfuscationThreshold() {
        return targetPicsureObfuscationThreshold;
    }

    public int getTargetPicsureObfuscationVariance() {
        return targetPicsureObfuscationVariance;
    }

    public String getTargetPicsureObfuscationSalt() {
        return targetPicsureObfuscationSalt;
    }

    public UUID getVisualizationResourceId() {
        return visualizationResourceId;
    }

    public void init(String contextPath) {
    	logger.info("initializing aggregate Resource properties");

    	this.contextPath = contextPath;

        Path configFile = Path.of(System.getProperty("jboss.server.config.dir"), "aggregate-data-sharing", contextPath, "resource.properties");
        Properties properties = null;
        try {
            properties = new Properties();
            properties.load(Files.newInputStream(configFile));
        } catch (IOException e) {
            throw new ApplicationException("Error while reading resource properties file: " + configFile, e);
        }

        targetPicsureUrl = properties.getProperty("target.picsure.url");
        if (targetPicsureUrl == null) {
            throw new PicsureQueryException("target.picsure.url property must be set.");
        }

        //target resource ID can be empty
        targetResourceId = properties.getProperty("target.resource.id");
		if (targetResourceId == null)
			targetResourceId = "";
			
        targetPicsureToken = properties.getProperty("target.picsure.token");
        if (targetPicsureToken == null) {
            throw new PicsureQueryException("target.picsure.token property must be set.");
        }

        String visualizationResourceUUID = properties.getProperty("visualization.resource.id");
        if (visualizationResourceUUID != null && !visualizationResourceUUID.trim().isEmpty()) {
            visualizationResourceId = UUID.fromString(visualizationResourceUUID);
                logger.debug("visualizationResourceId: " + visualizationResourceId);
                if (visualizationResourceId == null)
                throw new PicsureQueryException("visualization.resource.id property must be set.");
        }

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
