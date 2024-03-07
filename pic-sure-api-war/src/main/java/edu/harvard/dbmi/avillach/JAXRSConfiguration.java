package edu.harvard.dbmi.avillach;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.ejb.Startup;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

@Startup
@ApplicationPath("PICSURE")
public class JAXRSConfiguration extends Application {

    private Logger logger = LoggerFactory.getLogger(JAXRSConfiguration.class);

    public static String rolesClaim;

    public static String application_stack;

    @PostConstruct
    public void init() {
        logger.info("Starting pic-sure core app.");

        logger.info("Initializing roles claim.");
        initializeRolesClaim();
        logger.info("Finished initializing roles claim.");

        logger.info("Load optional properties.");
        initializeApplicationStack();
        logger.info("Finished loading optional properties.");
    }

    private void initializeApplicationStack() {
        try {
            Context ctx = new InitialContext();
            application_stack = (String) ctx.lookup("global/application_stack");
            ctx.close();
        } catch (NamingException e) {
            // Currently, this parameter is optional and only used if there is more than one application stack.
            application_stack = null;
        }
    }

    private void initializeRolesClaim() {
        try {
            Context ctx = new InitialContext();
            rolesClaim = (String) ctx.lookup("global/roles_claim");
            ctx.close();
        } catch (NamingException e) {
            rolesClaim = "privileges";
        }
    }

    public JAXRSConfiguration() {}

}
