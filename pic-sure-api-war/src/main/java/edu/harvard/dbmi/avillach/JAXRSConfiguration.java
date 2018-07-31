package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.security.JWTFilter;
import edu.harvard.dbmi.avillach.service.PicsureResourceService;
import edu.harvard.dbmi.avillach.service.PicsureUserService;
import edu.harvard.dbmi.avillach.service.SystemService;
import edu.harvard.dbmi.avillach.service.TokenService;
import io.swagger.jaxrs.config.BeanConfig;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;

@ApplicationPath("PICSURE")
public class JAXRSConfiguration extends Application {

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
