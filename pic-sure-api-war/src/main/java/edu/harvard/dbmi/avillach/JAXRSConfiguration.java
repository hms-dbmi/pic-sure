package edu.harvard.dbmi.avillach;

import io.swagger.jaxrs.config.BeanConfig;
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

    @PostConstruct
    public void init() {
        logger.info("Starting pic-sure core app.");

        logger.info("Initializing roles claim.");
        initializeRolesClaim();
        logger.info("Finished initializing roles claim.");

    }

    private void initializeRolesClaim(){
        try{
            Context ctx = new InitialContext();
            rolesClaim = (String) ctx.lookup("global/roles_claim");
            ctx.close();
        } catch (NamingException e) {
            rolesClaim = "privileges";
        }
    }

    public JAXRSConfiguration(){
        //Set info for the swagger.json
        //TODO Contact, termsofservice, and license
        BeanConfig beanConfig = new BeanConfig();
        beanConfig.setVersion("2.0");
        beanConfig.setSchemes(new String[] { "http" });
        beanConfig.setDescription("API to query multiple datasets");
        beanConfig.setHost("localhost:8080");
        beanConfig.setTitle("PIC-SURE 2.0 API");
        beanConfig.setBasePath("/PICSURE");
        beanConfig.setResourcePackage("edu.harvard.dbmi.avillach");
        beanConfig.setScan(true);
    }

//    @Override
//    public Set<Class<?>> getClasses() {
//        HashSet<Class<?>> set = new HashSet<Class<?>>();
//
//        set.add(PicsureRS.class);
//        //Add other services here
//        set.add(PicsureResourceService.class);
//        set.add(PicsureUserService.class);
//        set.add(SystemService.class);
//        set.add(TokenService.class);
//        set.add(JWTFilter.class);
//
//        set.add(io.swagger.jaxrs.listing.ApiListingResource.class);
//        set.add(io.swagger.jaxrs.listing.SwaggerSerializers.class);
//
//        return set;
//    }
}
