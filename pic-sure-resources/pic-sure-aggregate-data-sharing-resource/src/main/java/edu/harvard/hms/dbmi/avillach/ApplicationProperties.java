package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
    private String targetPicsureObfuscationThreshold;
    
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

    public String getTargetPicsureObfuscationThreshold() {
        return targetPicsureObfuscationThreshold;
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

        targetPicsureObfuscationThreshold = properties.getProperty("target.picsure.obfuscation_threshold");
        if (targetPicsureObfuscationThreshold == null) {
            throw new PicsureQueryException("target.picsure.obfuscation_threshold property must be set.");
        }
    }
}
