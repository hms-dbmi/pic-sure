package edu.harvard.dbmi.avillach.service;

import static edu.harvard.dbmi.avillach.util.Utilities.buildHttpClientContext;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.PicSureWarInit;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;

@Path("/system")
public class SystemService {
    static int max_test_frequency = 60000;

    static final String RUNNING = "RUNNING";

    static final String ONE_OR_MORE_COMPONENTS_DEGRADED = "ONE OR MORE COMPONENTS DEGRADED";

    Logger logger = LoggerFactory.getLogger(SystemService.class);

    @Inject
    PicSureWarInit picSureWarInit;

    String lastStatus = "UNTESTED";
    long lastStatusCheck = 0l;

    @Inject
    ResourceRepository resourceRepo;

    String token_introspection_url;
    String token_introspection_token;

    String defaultApplicationUUID;

    @PostConstruct
    public void init() {
        token_introspection_url = picSureWarInit.getToken_introspection_url();
        token_introspection_token = picSureWarInit.getToken_introspection_token();
        defaultApplicationUUID = picSureWarInit.getDefaultApplicationUUID();
        if (token_introspection_url == null || token_introspection_token == null) {
            throw new RuntimeException("token_introspection_url and token_introspection_token not configured");
        }
    }

    @GET
    @Path("/status")
    @Produces("text/plain")
    public String status() {
        // Because there is no auth on this service we limit actually performing the checking to 1 per minute to avoid DOS scenarios.
        long timeOfRequest = System.currentTimeMillis();
        if (timeOfRequest - lastStatusCheck < max_test_frequency) {
            return lastStatus;
        } else {
            lastStatusCheck = timeOfRequest;
            try {
                List<Resource> resourcesToTest = resourceRepo.list();
                if (resourcesToTest == null || resourcesToTest.isEmpty()) {
                    lastStatus = ONE_OR_MORE_COMPONENTS_DEGRADED;
                    return lastStatus;
                }

                // convert the default application uuid to an uuid object
                UUID defaultApplicationUUID = UUID.fromString(this.defaultApplicationUUID);

                // We need to remove open or auth HPDS from the resource list depending on the environment deployed.
                // This because both are included in the database, but only one is actually deployed.
                // if the name contains hpds and is not the default application uuid, remove it.
                resourcesToTest.removeIf(
                    resource -> resource.getName().toLowerCase(Locale.ENGLISH).contains("hpds") && !resource.getUuid().equals(defaultApplicationUUID)
                );

                // This proves the MySQL database is serving queries
                // This proves at least one resources is configured
                // This proves we can perform token introspection
                if (testPSAMAResponds() && testResourcesRespond(resourcesToTest)) { // This proves all resources are at least serving info
                                                                                    // requests.
                    lastStatus = RUNNING;
                    return lastStatus;
                } else {
                    lastStatus = ONE_OR_MORE_COMPONENTS_DEGRADED;
                }
            } catch (Exception e) {
                logger.error("One or more components degraded", e);
                lastStatus = ONE_OR_MORE_COMPONENTS_DEGRADED;
            }
            return lastStatus;
        }
    }

    private boolean testPSAMAResponds() throws UnsupportedOperationException, IOException {
        CloseableHttpClient client = PicSureWarInit.CLOSEABLE_HTTP_CLIENT;
        ObjectMapper json = PicSureWarInit.objectMapper;

        HttpPost post = new HttpPost(token_introspection_url);
        post.setEntity(new StringEntity("{}"));
        post.setHeader("Content-Type", "application/json");
        // Authorize into the token introspection endpoint
        post.setHeader("Authorization", "Bearer " + token_introspection_token);
        CloseableHttpResponse response = null;
        try {
            response = client.execute(post, buildHttpClientContext());
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(
                    "callTokenIntroEndpoint() error back from token intro host server [" + token_introspection_url + "]: "
                        + EntityUtils.toString(response.getEntity())
                );
                throw new ApplicationException(
                    "Token Introspection host server return " + response.getStatusLine().getStatusCode() + ". Please see the log"
                );
            }
            JsonNode responseContent = json.readTree(response.getEntity().getContent());
            if (!responseContent.get("active").asBoolean()) {
                // This is actually the expected response as we did not send a token in the token_introspection_request.
                return true;
            }

            return true;
        } finally {
            try {
                if (response != null) response.close();
            } catch (IOException ex) {
                logger.error("callTokenIntroEndpoint() IOExcpetion when closing http response: " + ex.getMessage());
            }
        }
    }

    private boolean testResourcesRespond(List<Resource> resourcesToTest) {
        for (Resource resource : resourcesToTest) {
            ResourceInfo info = new ResourceWebClient().info(resource.getResourceRSPath(), new GeneralQueryRequest());
            if (info == null) {
                return false;
            }
        }
        return true;
    }
}

