package edu.harvard.dbmi.avillach.security;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.PicSureWarInit;
import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.entity.AuthUser;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;

import java.io.*;
import java.util.*;

import static edu.harvard.dbmi.avillach.util.Utilities.buildHttpClientContext;

@Provider
public class JWTFilter implements ContainerRequestFilter {

    private final Logger logger = LoggerFactory.getLogger(JWTFilter.class);

    @Context
    UriInfo uriInfo;

    @Context
    ResourceInfo resourceInfo;

    @Inject
    ResourceRepository resourceRepo;

    @Inject
    ResourceWebClient resourceWebClient;

    @Resource(mappedName = "java:global/user_id_claim")
    private String userIdClaim;

    ObjectMapper mapper = new ObjectMapper();

    @Inject
    PicSureWarInit picSureWarInit;

    @Inject
    QueryRepository queryRepo;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        logger.debug("Entered jwtfilter.filter()...");

        if (uriInfo.getPath().endsWith("/openapi.json")) {
            return;
        }

        if (
            requestContext.getUriInfo().getPath().contentEquals("/system/status")
                && requestContext.getRequest().getMethod().contentEquals(HttpMethod.GET)
        ) {
            // GET calls to /system/status do not require authentication or authorization
            requestContext.setProperty("username", "SYSTEM_MONITOR");
        } else {
            // Everything else goes through PSAMA token introspection
            String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
            boolean isOpenAccessEnabled = picSureWarInit.isOpenAccessEnabled();
            if (
                (StringUtils.isBlank(authorizationHeader) && isOpenAccessEnabled)
                    || (StringUtils.isNotBlank(authorizationHeader) && authorizationHeader.length() <= 7 && isOpenAccessEnabled)
            ) {
                boolean isAuthorized = callOpenAccessValidationEndpoint(requestContext);
                if (!isAuthorized) {
                    logger.error("User is not authorized.");
                    requestContext.abortWith(PICSUREResponse.unauthorizedError("User is not authorized."));
                }

                // There is no user associated with open access request. In order to provide traceability,
                // we set the username to OPEN_ACCESS:<request IP>
                requestContext.setProperty("username", "OPEN_ACCESS:" + requestContext.getUriInfo().getRequestUri().getHost());
            } else {
                if (authorizationHeader == null || authorizationHeader.isEmpty()) {
                    throw new NotAuthorizedException("No authorization header found.");
                }

                String token = authorizationHeader.substring(6).trim();
                if (token.isEmpty()) {
                    throw new NotAuthorizedException("No token found in authorization header.");
                }

                String userForLogging = null;
                try {
                    AuthUser authenticatedUser = null;

                    authenticatedUser = callTokenIntroEndpoint(requestContext, token, userIdClaim);

                    if (authenticatedUser == null) {
                        logger.error("Cannot extract a user from token: {}", token);
                        throw new NotAuthorizedException("Cannot find or create a user");
                    }

                    userForLogging = authenticatedUser.getUserId();

                    // The request context wants to remember who the user is
                    requestContext.setProperty("username", userForLogging);
                    requestContext.setSecurityContext(new AuthSecurityContext(authenticatedUser, uriInfo.getRequestUri().getScheme()));
                    logger.info("User - {} - has just passed all the authentication and authorization layers.", userForLogging);
                } catch (NotAuthorizedException e) {
                    // the detail of this exception should be logged right before the exception thrown out
                    logger.error("User - {} - is not authorized. {}", userForLogging, e.getChallenges());
                    requestContext.abortWith(PICSUREResponse.unauthorizedError("User is not authorized. " + e.getChallenges()));
                } catch (Exception e) {
                    logger
                        .error("User - {} - is not authorized {} and an Inner application error occurred.", userForLogging, e.getMessage());
                    requestContext.abortWith(PICSUREResponse.applicationError("Inner application error, please contact system admin"));
                }
            }
        }
    }

    /**
     *
     * @param token
     * @param userIdClaim
     * @return
     * @throws IOException
     */

    private AuthUser callTokenIntroEndpoint(ContainerRequestContext requestContext, String token, String userIdClaim) {
        logger.debug("TokenIntrospection - extractUserFromTokenIntrospection() starting...");

        String token_introspection_url = picSureWarInit.getToken_introspection_url();
        String token_introspection_token = picSureWarInit.getToken_introspection_token();

        if (token_introspection_url.isEmpty()) throw new ApplicationException("token_introspection_url is empty");

        if (token_introspection_token.isEmpty()) {
            throw new ApplicationException("token_introspection_token is empty");
        }

        ObjectMapper json = PicSureWarInit.objectMapper;
        CloseableHttpClient client = PicSureWarInit.CLOSEABLE_HTTP_CLIENT;

        HttpPost post = new HttpPost(token_introspection_url);

        Map<String, Object> tokenMap = new HashMap<>();
        tokenMap.put("token", token);

        Map<String, Object> requestMap = prepareRequestMap(requestContext);
        tokenMap.put("request", requestMap);

        StringEntity entity = null;
        try {
            entity = new StringEntity(json.writeValueAsString(tokenMap));
        } catch (IOException e) {
            logger.error("callTokenIntroEndpoint() - " + e.getClass().getSimpleName() + " when composing post");
            return null;
        }
        post.setEntity(entity);
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
                logger.info(
                    "This callTokenIntroEndpoint error can happen when your introspection token has expired. "
                        + "You can fix this by running the Configure PIC-SURE Token Introspection Token job in Jenkins."
                );
                throw new ApplicationException(
                    "Token Introspection host server return " + response.getStatusLine().getStatusCode() + ". Please see the log"
                );
            }
            JsonNode responseContent = json.readTree(response.getEntity().getContent());
            if (!responseContent.get("active").asBoolean()) {
                logger.error("callTokenIntroEndpoint() Token intro endpoint return invalid token, content: " + responseContent);
                throw new NotAuthorizedException("Token invalid or expired");
            }

            if (responseContent.has("tokenRefreshed") && responseContent.get("tokenRefreshed").asBoolean()) {
                requestContext.setProperty("refreshedToken", responseContent.get("token"));
            }

            String userId = responseContent.get(userIdClaim) != null ? responseContent.get(userIdClaim).asText() : null;
            String sub = responseContent.get("sub") != null ? responseContent.get("sub").asText() : null;
            String email = responseContent.get("email") != null ? responseContent.get("email").asText() : null;
            String roles = responseContent.get("roles") != null ? responseContent.get("roles").asText() : null;
            AuthUser user = new AuthUser().setUserId(userId).setSubject(sub).setEmail(email).setRoles(roles);
            return user;
        } catch (IOException ex) {
            logger.error("callTokenIntroEndpoint() IOException when hitting url: " + post + " with exception msg: " + ex.getMessage());
        } finally {
            try {
                if (response != null) response.close();
            } catch (IOException ex) {
                logger.error("callTokenIntroEndpoint() IOExcpetion when closing http response: " + ex.getMessage());
            }
        }

        return null;
    }

    private HashMap<String, Object> prepareRequestMap(ContainerRequestContext requestContext) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        HashMap<String, Object> requestMap = new HashMap<String, Object>();
        try {
            String requestPath = requestContext.getUriInfo().getPath();
            requestMap.put("Target Service", requestPath);

            Query initialQuery = null;
            // Read the query from the backing store if we are getting the results (full query may not be specified in request)
            if (requestPath.startsWith("/query/") && (requestPath.endsWith("result") || requestPath.endsWith("result/"))) {
                // Path: /query/{queryId}/result
                String[] pathParts = requestPath.split("/");
                UUID uuid = UUID.fromString(pathParts[2]);
                initialQuery = queryRepo.getById(uuid);
            }

            if (initialQuery != null) {
                IOUtils.copy(new ByteArrayInputStream(initialQuery.getQuery().getBytes()), buffer);
            } else {
                // This stream is only consumable once, so we need to save & reset it.
                InputStream entityStream = requestContext.getEntityStream();
                IOUtils.copy(entityStream, buffer);
                requestContext.setEntityStream(new ByteArrayInputStream(buffer.toByteArray()));
            }

            if (buffer.size() > 0) {
                /*
                 * We remove the resourceCredentials from the token introspection copy of the query to prevent logging them as part of token
                 * introspection. These credentials are between the backing resource and the user, PIC-SURE should do its best to keep them
                 * confidential.
                 */
                Object queryObject = new ObjectMapper().readValue(new ByteArrayInputStream(buffer.toByteArray()), Object.class);
                if (queryObject instanceof Collection) {
                    for (Object query : (Collection) queryObject) {
                        if (query instanceof Map) {
                            ((Map) query).remove("resourceCredentials");
                        }
                    }
                } else if (queryObject instanceof Map) {
                    ((Map) queryObject).remove("resourceCredentials");
                }
                requestMap.put("query", queryObject);

                if (requestPath.startsWith("/query/")) {

                    UUID resourceUUID = null;
                    String resourceUUIDStr = (String) ((Map) queryObject).get("resourceUUID");
                    if (resourceUUIDStr != null) {
                        resourceUUID = UUID.fromString(resourceUUIDStr);
                    }

                    if (resourceUUID != null) {
                        edu.harvard.dbmi.avillach.data.entity.Resource resource = resourceRepo.getById(resourceUUID);
                        // logger.info("resource obj: " + resource + " path: " + resource.getResourceRSPath());
                        if (resource != null && resource.getResourceRSPath() != null) {
                            GeneralQueryRequest queryRequest = new GeneralQueryRequest();
                            queryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
                            queryRequest.setResourceUUID(resourceUUID);
                            queryRequest.setQuery(((Map) queryObject).get("query"));

                            Response formatResponse = resourceWebClient.queryFormat(resource.getResourceRSPath(), queryRequest);
                            if (formatResponse.getStatus() == 200) {
                                // add the formatted query if available
                                String formattedQuery = IOUtils.toString((InputStream) formatResponse.getEntity(), "UTF-8");
                                logger.debug("Formatted response: " + formattedQuery);
                                requestMap.put("formattedQuery", formattedQuery);
                            }
                        }
                    }
                }
            }
            return requestMap;
        } catch (JsonParseException ex) {
            requestMap.put("query", buffer.toString());
            return requestMap;
        } catch (IOException e1) {
            logger.error("IOException caught trying to build requestMap for auditing.", e1);
            throw new NotAuthorizedException(
                "The request could not be properly audited. If you recieve this error multiple times, please contact an administrator."
            );
        }
    }

    private boolean callOpenAccessValidationEndpoint(ContainerRequestContext requestContext) {
        String openAccessValidateUrl = picSureWarInit.getOpenAccessValidateUrl();
        String token_introspection_token = picSureWarInit.getToken_introspection_token();

        if (openAccessValidateUrl.isEmpty()) {
            throw new ApplicationException("callOpenAccessValidationEndpoint - openAccessValidateUrl is empty in application properties");
        }

        Map<String, Object> requestMap = new HashMap<>();
        Map<String, Object> queryMap = prepareRequestMap(requestContext);
        requestMap.put("request", queryMap);
        // There is no user associated with open access request. In order to provide traceability,
        // we set the username to OPEN_ACCESS:<request IP>
        requestMap.put("ipAddress", "OPEN_ACCESS:" + requestContext.getUriInfo().getRequestUri().getHost());
        ObjectMapper json = PicSureWarInit.objectMapper;

        StringEntity entity = null;
        try {
            entity = new StringEntity(json.writeValueAsString(requestMap));
        } catch (IOException e) {
            logger.error("callOpenAccessValidationEndpoint() - FAILED TO parse requestMap to json", e);
            return false;
        }

        CloseableHttpClient client = PicSureWarInit.CLOSEABLE_HTTP_CLIENT;
        HttpPost post = new HttpPost(openAccessValidateUrl);
        post.setEntity(entity);
        post.setHeader("Content-Type", "application/json");
        // Authorize into the token introspection endpoint
        post.setHeader("Authorization", "Bearer " + token_introspection_token);
        CloseableHttpResponse response = null;
        boolean isValid = false;
        try {
            response = client.execute(post, buildHttpClientContext());

            if (response.getStatusLine().getStatusCode() == 200) {

                // A 200 is return as long as the request is successful, the actual validation result is in the response body
                JsonNode responseContent = json.readTree(response.getEntity().getContent());
                if (!responseContent.isBoolean()) {
                    logger.error(
                        "callOpenAccessValidateEndpoint() Open access validate endpoint return invalid response, content: {}",
                        responseContent
                    );
                    throw new ApplicationException("Open access validate endpoint return invalid response");
                }

                isValid = responseContent.asBoolean();
            } else {
                logger.error(
                    "callOpenAccessValidateEndpoint() error back from open access validate host server [{}]: {}", openAccessValidateUrl,
                    EntityUtils.toString(response.getEntity())
                );
                throw new ApplicationException(
                    "Open access validate host server returned " + response.getStatusLine().getStatusCode() + ". Please see the log"
                );
            }

        } catch (IOException ex) {
            logger.error("callOpenAccessValidateEndpoint() IOException when hitting url: {} with exception msg: {}", post, ex.getMessage());
        } finally {
            try {
                if (response != null) response.close();
            } catch (IOException ex) {
                logger.error("callOpenAccessValidateEndpoint() IOException when closing http response: {}", ex.getMessage());
            }
        }

        return isValid;
    }

    void setUserIdClaim(String userIdClaim) {
        this.userIdClaim = userIdClaim;
    }
}
