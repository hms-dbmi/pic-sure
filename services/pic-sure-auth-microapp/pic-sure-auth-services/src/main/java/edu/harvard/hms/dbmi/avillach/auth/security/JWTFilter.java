package edu.harvard.hms.dbmi.avillach.auth.security;

import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * The main gate for PSAMA that filters all incoming requests against PSAMA.
 * <h3>Design Logic</h3>
 * <ul>
 *     <li>All incoming requests pass through this filter.</li>
 *     <li>To pass this filter, the incoming request needs a valid bearer token in its HTTP Authorization Header
 *     to represent a valid identity behind the token. </li>
 *     <li>In some cases, the incoming request doesn't need to hold a token. For example, when the request is to the <code>authentication</code>
 *     endpoint, <code>swagger.json</code>, or <code>swagger.html</code>.</li>
 * </ul>
 */
@Provider
public class JWTFilter implements ContainerRequestFilter {

	@Context
	private UriInfo uriInfo;

	Logger logger = LoggerFactory.getLogger(JWTFilter.class);

	@Context
	ResourceInfo resourceInfo;

	@Inject
	UserRepository userRepo;

	@Inject
	ApplicationRepository applicationRepo;

	@Inject
	TOSService tosService;

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {

		/**
		 * skip the filter in certain cases
		 */
		if (uriInfo.getPath().endsWith("authentication")
				|| uriInfo.getPath().endsWith("/swagger.yaml")
				|| uriInfo.getPath().endsWith("/swagger.json")) {
			return;
		}

		logger.debug("Entered jwtfilter.filter()...");

		try {
			String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
			if (authorizationHeader == null || authorizationHeader.isEmpty()) {
				throw new NotAuthorizedException("No authorization header found.");
			}
			String token = authorizationHeader.substring(6).trim();

			String userForLogging = null;

			Jws<Claims> jws = parseToken(token);

			String userId = jws.getBody().get(JAXRSConfiguration.userIdClaim, String.class);

			if(userId.startsWith(AuthNaming.LONG_TERM_TOKEN_PREFIX)) {
				/**
				 * authenticate if a long term token is presented.
				 * PSAMA activities should not be able to execute under this token.
				 * Normal admin should access psama through psamaui, the token will be expired
				 * in a certain time.
				 * If super admin want to access the psama through APIs, the token should
				 * be grabbed from psamaui as well to prevent the token leakage.
				 */
				logger.error("filter() the long term token with subject, " + userId + ", cannot access to PSAMA.");
				throw new NotAuthorizedException("Long term tokens cannot be used to access to PSAMA.");

			} else if(userId.startsWith(AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX)) {
				/**
				 * authenticate as Application, we might need to extract this blob out to an separate function
				 */

				if( ! uriInfo.getPath().endsWith("token/inspect")) {
					logger.error(userId + " attempted to perform request " + uriInfo.getPath() + " token may be compromised.");
					throw new NotAuthorizedException("User is deactivated");
				}
				Application authenticatedApplication = applicationRepo.getById(UUID.fromString(userId.split("\\|")[1]));
				if (authenticatedApplication == null){
					logger.error("Cannot find an application by userId: " + userId);
					throw new NotAuthorizedException("Your token doesn't contain valid identical information, please contact admin.");
				}

				if (!authenticatedApplication.getToken().equals(token)) {
					logger.error("filter() incoming application token - " + token +
							" - is not the same as record, might because the token has been refreshed. Subject: " + userId);
					throw new NotAuthorizedException("Your token has been inactivated, please contact admin to grab you the latest one.");
				}

				requestContext.setSecurityContext(new AuthSecurityContext(authenticatedApplication,
						uriInfo.getRequestUri().getScheme()));
			} else {
				/**
				 * authenticate as User, we might need to extract this blob out to an separate function
				 */

				User authenticatedUser = callLocalAuthentication(requestContext, jws);

				/**
				 * This TOSService code will hit to the database to retrieve a user once again
				 */
				if (!uriInfo.getPath().contains("/tos")){
					if (JAXRSConfiguration.tosEnabled.startsWith("true") && tosService.getLatest() != null && !tosService.hasUserAcceptedLatest(authenticatedUser.getSubject())){
						//If user has not accepted terms of service and is attempted to get information other than the terms of service, don't authenticate
						requestContext.abortWith(Response.status(Response.Status.FORBIDDEN).entity("User must accept terms of service").build());
						return;
					}
				}
				if (authenticatedUser == null) {
					logger.error("Cannot extract a user from token: " + token);
					throw new NotAuthorizedException("Cannot find or create a user");
				}
				// Check whether user is active
				if (!authenticatedUser.isActive()) {
					logger.warn("User with ID: " + authenticatedUser.getUuid() + " is deactivated.");
					throw new NotAuthorizedException("User is deactivated");
				}
				// currently only user id will be logged, in the future, it might contain roles and other information,
				// like xxxuser|roles|otherInfo
				userForLogging = authenticatedUser.getSubject();
				// check authorization of the authenticated user
				checkRoles(authenticatedUser, resourceInfo
						.getResourceMethod().isAnnotationPresent(RolesAllowed.class)
						? resourceInfo.getResourceMethod().getAnnotation(RolesAllowed.class).value()
								: new String[]{});

				logger.info("User - " + userForLogging + " - has just passed all the authentication and authorization layers.");

				requestContext.setSecurityContext(new AuthSecurityContext(authenticatedUser,
						uriInfo.getRequestUri().getScheme()));

			}

		} catch (NotAuthorizedException e) {
			// the detail of this exception should be logged right before the exception thrown out
			//			logger.error("User - " + userForLogging + " - is not authorized. " + e.getChallenges());
			// we should show different response based on role
			requestContext.abortWith(PICSUREResponse.unauthorizedError("User is not authorized. " + e.getChallenges()));
		} catch (ApplicationException e){
			// we should show different response based on role
			e.printStackTrace();
			requestContext.abortWith(PICSUREResponse.applicationError(e.getContent()));
		} catch (Exception e) {
			e.printStackTrace();
			requestContext.abortWith(PICSUREResponse.applicationError(e.getMessage()));
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

		String logMsg = "The roles of the user - id: " + authenticatedUser.getSubject() + " - "; //doesn't match the required restrictions";
		boolean b = true;
		if (rolesAllowed.length < 1) {
			return true;
		}


		if (authenticatedUser.getRoles() == null) {
			logger.error(logMsg + "is null.");
			throw new NotAuthorizedException("user doesn't have an assigned role.");
		}

		Set<String> privilegeNameSet = authenticatedUser.getPrivilegeNameSet();
		if (privilegeNameSet.isEmpty()){
			logger.error(logMsg + "doesn't have privileges associated.");
			throw new NotAuthorizedException("user doesn't have roles or privileges, please contact admin.");
		}

		boolean isAuthorized = false;
		for (String role : rolesAllowed) {
			if(privilegeNameSet.contains(role.trim())) {
				isAuthorized = true;
				break;
			}
		}
		if(!isAuthorized) {
			logger.error(logMsg + "doesn't match the required role/privilege restrictions, privileges from user: "
					+ authenticatedUser.getPrivilegeString() + ", priviliges required: " + Arrays.toString(rolesAllowed));
			throw new NotAuthorizedException("doesn't match the required role restrictions.");
		}
		return b;
	}

	/**
	 *
	 * @param requestContext
	 * @return
	 * @throws NotAuthorizedException
	 */
	private User callLocalAuthentication(ContainerRequestContext requestContext, Jws<Claims> jws) throws NotAuthorizedException{
		String subject = jws.getBody().getSubject();
		String userId = jws.getBody().get(JAXRSConfiguration.userIdClaim, String.class);

		return userRepo.findOrCreate(new User().setSubject(subject));
	}

	private Jws<Claims> parseToken(String token) {
		Jws<Claims> jws = AuthUtils.parseToken(JAXRSConfiguration.clientSecret, token);
		return jws;
	}
}
