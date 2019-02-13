package edu.harvard.dbmi.avillach.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import edu.harvard.dbmi.avillach.JAXRSConfiguration;
import edu.harvard.dbmi.avillach.PicSureWarInit;
import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.data.repository.UserRepository;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
	UserRepository userRepo;

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

			if (PicSureWarInit.VERIFY_METHOD_TOKEN_INTRO.equalsIgnoreCase(picSureWarInit.getVerify_user_method())) {
				authenticatedUser = callTokenIntroEndpoint(token, userIdClaim, JAXRSConfiguration.rolesClaim);
			} else {
				authenticatedUser = callLocalAuthentication(requestContext, token);
			}

			if (authenticatedUser == null) {
				logger.error("Cannot extract a user from token: " + token + ", verify method: " + picSureWarInit.getVerify_user_method());
				throw new NotAuthorizedException("Cannot find or create a user");
			}

			// currently only user id will be logged, in the future, it might contain roles and other information,
			// like xxxuser|roles|otherInfo
			userForLogging = authenticatedUser.getUserId();

			//The request context wants to remember who the user is
			requestContext.setProperty("username", userForLogging);

			// check authorization of the authenticated user
			checkRoles(authenticatedUser, resourceInfo
					.getResourceMethod().isAnnotationPresent(RolesAllowed.class)
					? resourceInfo.getResourceMethod().getAnnotation(RolesAllowed.class).value()
					: new String[]{});

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
	 * check if user contains the input list of roles
	 *
	 * @param authenticatedUser
	 * @param rolesAllowed
	 * @return
	 */
	private boolean checkRoles(User authenticatedUser, String[] rolesAllowed) throws NotAuthorizedException{

		String logMsg = "The roles of the user - id: " + authenticatedUser.getUserId() + " - "; //doesn't match the required restrictions";
		boolean b = true;
		if (rolesAllowed.length < 1) {
			return true;
		}

		if (authenticatedUser.getRoles() == null) {
			logger.error(logMsg + "user doesn't have a role.");
			throw new NotAuthorizedException("user doesn't have a role.");
		}

		for (String role : rolesAllowed) {
			if(!authenticatedUser.getRoles().contains(role)) {
				logger.error(logMsg + "doesn't match the required role restrictions, role from user: "
						+ authenticatedUser.getRoles() + ", role required: " + Arrays.toString(rolesAllowed));
				throw new NotAuthorizedException("doesn't match the required role restrictions.");
			}
		}
		return b;
	}

	/**
	 *
	 * @param token
	 * @param userIdClaim
	 * @return
	 * @throws IOException
	 */
	private User callTokenIntroEndpoint(String token, String userIdClaim, String rolesClaim) {
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

		Map<String, String> tokenMap = new HashMap<>();
		tokenMap.put("token", token);
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
			ArrayNode jsonRoles = responseContent.get(rolesClaim) != null ? (ArrayNode)responseContent.get(rolesClaim) : null;
			String userRoles = "";
			if (jsonRoles != null) {
				for (JsonNode role : jsonRoles) {
					userRoles += (role.textValue() + " ");
				}
			}
			logger.debug("Roles for user from introspection: " + userRoles);
			return new User().setSubject(sub).setUserId(sub).setRoles(userRoles.trim());
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

	private User callLocalAuthentication(ContainerRequestContext requestContext, String token) throws JwtException{
		Jws<Claims> jws = Jwts.parser().setSigningKey(clientSecret.getBytes()).parseClaimsJws(token);

		String subject = jws.getBody().getSubject();
		String userId = jws.getBody().get(userIdClaim, String.class);

		return userRepo.findOrCreate(new User().setSubject(subject).setUserId(userId));
	}
}
