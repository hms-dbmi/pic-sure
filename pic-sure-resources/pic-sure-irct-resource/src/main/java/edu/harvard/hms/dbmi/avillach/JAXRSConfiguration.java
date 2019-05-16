package edu.harvard.hms.dbmi.avillach;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import org.apache.commons.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Startup;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContextListener;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

@Startup
@ApplicationPath("pic-sure")
public class JAXRSConfiguration extends Application implements ServletContextListener {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    public static final long DEFAULT_MAXIMUM_SEARCH_SIZE = 5000L;
    public static Long MAXIMUM_SEARCH_SIZE;

    // an Age search result wouldn't beyond 10M length
    public static final long DEFAULT_MAXIMUM_WEIGHT = 10000000L;
    public static Long MAXIMUM_WEIGHT;

    public static String search_token;
    public static boolean loadingCacheEnabled = false;

	public JAXRSConfiguration() {
        // loading maximum search size
        try {
            if ((MAXIMUM_SEARCH_SIZE = Long.getLong("MAXIMUM_SEARCH_SIZE")) == null) {
                logger.error("IRCTResourceRS() failed loading MAXIMUM_SEARCH_SIZE, " +
                        "set size back to default: " + DEFAULT_MAXIMUM_SEARCH_SIZE);
                MAXIMUM_SEARCH_SIZE = DEFAULT_MAXIMUM_SEARCH_SIZE;
            }
        } catch (SecurityException ex){
            logger.error("IRCTResourceRS() loading MAXIMUM_SEARCH_SIZE throws SecurityException. " +
                    "Set size back to default: " + DEFAULT_MAXIMUM_SEARCH_SIZE);
        }

        // loading maximum search weight
        try {
            if ((MAXIMUM_WEIGHT = Long.getLong("MAXIMUM_WEIGHT")) == null) {
                logger.error("IRCTResourceRS() failed loading MAXIMUM_WEIGHT, " +
                        "set size back to default: " + DEFAULT_MAXIMUM_WEIGHT);
                MAXIMUM_WEIGHT = DEFAULT_MAXIMUM_WEIGHT;
            }
        } catch (SecurityException ex){
            logger.error("IRCTResourceRS() loading MAXIMUM_WEIGHT throws SecurityException. " +
                    "Set size back to default: " + DEFAULT_MAXIMUM_WEIGHT);
        }

        try {
            InitialContext ctx = new InitialContext();
            search_token = (String) ctx.lookup("java:global/SEARCH_TOKEN");
            loadingCacheEnabled = true;
        } catch (NamingException ex){
            logger.error("picsure-irct-resource has not set search_token, loadingCache disabled.");
            loadingCacheEnabled = false;
        }

	}

}
