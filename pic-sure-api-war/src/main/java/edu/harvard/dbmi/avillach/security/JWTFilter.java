package edu.harvard.dbmi.avillach.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import javax.annotation.Resource;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.data.repository.UserRepository;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import io.jsonwebtoken.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	UserRepository userRepo;

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {
		logger.debug("Entered jwtfilter.filter()...");
		String tokenForLogging = null;
		String userForLogging = null;

		String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
		if (authorizationHeader == null || authorizationHeader.isEmpty()) {
			throw new NotAuthorizedException("No authorization header found.");
		}
		String token = authorizationHeader.substring(6).trim();
		tokenForLogging = token;

		try {
			Jws<Claims> jws = Jwts.parser().setSigningKey(clientSecret.getBytes()).parseClaimsJws(token);

			String subject = jws.getBody().getSubject();
			String userId = jws.getBody().get(userIdClaim, String.class);
						
			User authenticatedUser = userRepo.findOrCreate(subject, userId);

			if (authenticatedUser == null) {
				logger.error("Cannot find or create a user from token: " + token);
				throw new NotAuthorizedException("Cannot find or create a user");
			}

			// currently only user id will be logged, in the future, it might contain roles and other information,
			// like xxxuser|roles|otherInfo
			userForLogging = authenticatedUser.getUserId();

			// check authorization of the authenticated user
			checkRoles(authenticatedUser, resourceInfo
					.getResourceMethod().isAnnotationPresent(RolesAllowed.class)
					? resourceInfo.getResourceMethod().getAnnotation(RolesAllowed.class).value()
					: new String[]{});

			logger.info("User - " + userForLogging + " - has just passed all the authentication and authorization layers.");

		} catch (JwtException e) {
			logger.error("Exception "+ e.getClass().getSimpleName()+": token - " + tokenForLogging + " - is invalid: " + e.getMessage());
			requestContext.abortWith(PICSUREResponse.unauthorizedError("Token is invalid."));
		} catch (NotAuthorizedException e) {
			logger.error("User - " + userForLogging + " - is not authorized. " + e.getChallenges());
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
				logger.error(logMsg + "doesn't match the required restrictions, role from user: "
						+ authenticatedUser.getRoles() + ", role required: " + Arrays.toString(rolesAllowed));
				throw new NotAuthorizedException("doesn't match the required restrictions.");
			}
		}
		return b;
	}

}
