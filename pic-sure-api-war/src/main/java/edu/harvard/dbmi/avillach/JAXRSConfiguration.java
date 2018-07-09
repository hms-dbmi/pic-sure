package edu.harvard.dbmi.avillach;

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

    @Override
    public Set<Class<?>> getClasses() {
        HashSet<Class<?>> set = new HashSet<Class<?>>();

        set.add(PicsureRS.class);

        set.add(io.swagger.jaxrs.listing.ApiListingResource.class);
        set.add(io.swagger.jaxrs.listing.SwaggerSerializers.class);

        return set;
    }
}
