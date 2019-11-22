package edu.harvard.dbmi.avillach.security;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.PicSureWarInit;
import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import io.jsonwebtoken.JwtException;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static edu.harvard.dbmi.avillach.util.Utilities.applyProxySettings;
import static edu.harvard.dbmi.avillach.util.Utilities.buildHttpClientContext;

@Provider
public class JWTFilter implements ContainerRequestFilter {

	Logger logger = LoggerFactory.getLogger(JWTFilter.class);

	@Context
	ResourceInfo resourceInfo;

	@Resource(mappedName = "java:global/client_secret")
	private String clientSecret;
	@Resource(mappedName = "java:global/user_id_claim")
	private String userIdClaim;

	@Inject
	PicSureWarInit picSureWarInit;
	
	@Inject
	QueryRepository queryRepo;

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {
		logger.debug("Entered jwtfilter.filter()...");

		String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
		if (authorizationHeader == null || authorizationHeader.isEmpty()) {
			throw new NotAuthorizedException("No authorization header found.");
		}
		String token = authorizationHeader.substring(6).trim();

		String userForLogging = null;

		try {
			User authenticatedUser = null;

			authenticatedUser = callTokenIntroEndpoint(requestContext, token, userIdClaim);

			if (authenticatedUser == null) {
				logger.error("Cannot extract a user from token: " + token);
				throw new NotAuthorizedException("Cannot find or create a user");
			}

			userForLogging = authenticatedUser.getUserId();

			//The request context wants to remember who the user is
			requestContext.setProperty("username", userForLogging);

			logger.info("User - " + userForLogging + " - has just passed all the authentication and authorization layers.");

		} catch (JwtException e) {
			logger.error("Exception "+ e.getClass().getSimpleName()+": token - " + token + " - is invalid: " + e.getMessage());
			requestContext.abortWith(PICSUREResponse.unauthorizedError("Token is invalid."));
		} catch (NotAuthorizedException e) {
			// the detail of this exception should be logged right before the exception thrown out
			//			logger.error("User - " + userForLogging + " - is not authorized. " + e.getChallenges());
			// we should show different response based on role
			requestContext.abortWith(PICSUREResponse.unauthorizedError("User is not authorized. " + e.getChallenges()));
		} catch (Exception e){
			// we should show different response based on role
			e.printStackTrace();
			requestContext.abortWith(PICSUREResponse.applicationError("Inner application error, please contact system admin"));
		}
	}

	/**
	 *
	 * @param token
	 * @param userIdClaim
	 * @return
	 * @throws IOException
	 */

	private User callTokenIntroEndpoint(ContainerRequestContext requestContext, String token, String userIdClaim) {
		logger.debug("TokenIntrospection - extractUserFromTokenIntrospection() starting...");

		String token_introspection_url = picSureWarInit.getToken_introspection_url();
		String token_introspection_token = picSureWarInit.getToken_introspection_token();

		if (token_introspection_url.isEmpty())
			throw new ApplicationException("token_introspection_url is empty");

		if (token_introspection_token.isEmpty()){
			throw new ApplicationException("token_introspection_token is empty");
		}

		ObjectMapper json = PicSureWarInit.objectMapper;
		CloseableHttpClient client = PicSureWarInit.CLOSEABLE_HTTP_CLIENT;

		HttpPost post = new HttpPost(token_introspection_url);
		applyProxySettings(post);

		Map<String, Object> tokenMap = new HashMap<>();
		tokenMap.put("token", token);
		
		
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		HashMap<String, Object> requestMap = new HashMap<String, Object>();
		try {

			String requestPath = requestContext.getUriInfo().getPath();
			requestMap.put("Target Service", requestPath);
			
			//Read the query from the backing store if we are getting the results (full query may not be specified in request)
			if(requestPath.startsWith("/query/") && requestPath.endsWith("result")) {
				 //Path:   /query/{queryId}/result
				String[] pathParts = requestPath.split("/");
				UUID uuid = UUID.fromString(pathParts[2]);
				Query query = queryRepo.getById(uuid);
				IOUtils.copy(new ByteArrayInputStream(query.getQuery().getBytes()), buffer);
			} else {
				//This stream is only consumable once, so we need to save & reset it.
				InputStream entityStream = requestContext.getEntityStream();
				IOUtils.copy(entityStream, buffer);
				requestContext.setEntityStream(new ByteArrayInputStream(buffer.toByteArray()));
			}
			
			if(buffer.size()>0) {
				//I think here we are removing any existing credentials from the query; PIC-SURE has it's own static token that will be used
				Object queryObject = new ObjectMapper().readValue(new ByteArrayInputStream(buffer.toByteArray()), Object.class);
				if (queryObject instanceof Collection) {
					for (Object query: (Collection)queryObject) {
						if (query instanceof Map)
							((Map) query).remove("resourceCredentials");
					}
				} else if (queryObject instanceof Map){
					((Map) queryObject).remove("resourceCredentials");
				}
				requestMap.put("query", queryObject);
			}
			tokenMap.put("request", requestMap);
		} catch (JsonParseException ex) {
			requestMap.put("query",buffer.toString());
			tokenMap.put("request", requestMap);
		} catch (IOException e1) {
			logger.error("IOException caught trying to build requestMap for auditing.", e1);
			throw new NotAuthorizedException("The request could not be properly audited. If you recieve this error multiple times, please contact an administrator.");
		}
		StringEntity entity = null;
		try {
			entity = new StringEntity(json.writeValueAsString(tokenMap));
		} catch (IOException e) {
			logger.error("callTokenIntroEndpoint() - " + e.getClass().getSimpleName() + " when composing post");
			return null;
		}
		post.setEntity(entity);
		post.setHeader("Content-Type", "application/json");
		//Authorize into the token introspection endpoint
		post.setHeader("Authorization", "Bearer " + token_introspection_token);
		CloseableHttpResponse response = null;
		try {
			response = client.execute(post, buildHttpClientContext());
			if (response.getStatusLine().getStatusCode() != 200){
				logger.error("callTokenIntroEndpoint() error back from token intro host server ["
						+ token_introspection_url + "]: " + EntityUtils.toString(response.getEntity()));
				throw new ApplicationException("Token Introspection host server return " + response.getStatusLine().getStatusCode() +
						". Please see the log");
			}
			JsonNode responseContent = json.readTree(response.getEntity().getContent());
			if (!responseContent.get("active").asBoolean()){
				logger.error("callTokenIntroEndpoint() Token intro endpoint return invalid token, content: " + responseContent);
				throw new NotAuthorizedException("Token invalid or expired");
			}

			String sub = responseContent.get(userIdClaim) != null ? responseContent.get(userIdClaim).asText() : null;
			User user = new User().setSubject(sub).setUserId(sub);
			return user;
		} catch (IOException ex){
			logger.error("callTokenIntroEndpoint() IOException when hitting url: " + post
					+ " with exception msg: " + ex.getMessage());
		} finally {
			try {
				if (response != null)
					response.close();
			} catch (IOException ex) {
				logger.error("callTokenIntroEndpoint() IOExcpetion when closing http response: " + ex.getMessage());
			}
		}

		return null;
	}
}
