package edu.harvard.dbmi.avillach.security;

import java.io.IOException;
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
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
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
		try {
			String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

			String token = authorizationHeader.substring(6).trim();

			Jws<Claims> jws = Jwts.parser().setSigningKey(clientSecret.getBytes()).parseClaimsJws(token);

			String subject = jws.getBody().getSubject();
			
			String userId = jws.getBody().get(userIdClaim, String.class);
						
			User authenticatedUser = userRepo.findOrCreate(subject, userId);

			if (authenticatedUser == null)
				requestContext.abortWith(PICSUREResponse.unauthorizedError("Cannot find or create a user"));

			String[] rolesAllowed = resourceInfo.getResourceMethod().isAnnotationPresent(RolesAllowed.class)
					? resourceInfo.getResourceMethod().getAnnotation(RolesAllowed.class).value()
							: new String[]{};
			for(String role : rolesAllowed) {
				if(authenticatedUser.getRoles() == null
					|| !authenticatedUser.getRoles().contains(role)) {
					requestContext.abortWith(PICSUREResponse.unauthorizedError("User has insufficient privileges."));
				}
			}
			
		} catch (NotAuthorizedException e) {
			// we should show different response based on role
			requestContext.abortWith(PICSUREResponse.unauthorizedError("User has insufficient privileges."));
		} catch (Exception e){
			// we should show different response based on role
			e.printStackTrace();
			requestContext.abortWith(PICSUREResponse.applicationError("Inner application user, please contact system admin"));
		}
	}

}
