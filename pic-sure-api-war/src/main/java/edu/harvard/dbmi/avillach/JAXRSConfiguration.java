package edu.harvard.dbmi.avillach;

import io.swagger.v3.jaxrs2.integration.JaxrsOpenApiContextBuilder;
import io.swagger.v3.oas.integration.OpenApiConfigurationException;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.ejb.Startup;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletConfig;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        logger.info("Initializing OpenAPI.");
        initializeOpenAPI();
        logger.info("Finished initializing OpenAPI.");
    }

    private void initializeOpenAPI() {
        // initialize the OpenAPI configuration
        SwaggerConfiguration oasConfig = new SwaggerConfiguration()
                .openAPI(new OpenAPI()
                        .info(new Info()
                                .title("PICSURE API")
                                .description("PICSURE API")
                                .version("1.0.0")))
                .prettyPrint(true)
                .resourcePackages(Stream.of("edu.harvard.dbmi.avillach").collect(Collectors.toSet()));

        try {
            new JaxrsOpenApiContextBuilder<>()
                    .servletConfig(null)
                    .application(this)
                    .openApiConfiguration(oasConfig)
                    .buildContext(true);
        } catch (OpenApiConfigurationException e) {
            logger.error("Failed to initialize OpenAPI.", e);
        }
    }
}
