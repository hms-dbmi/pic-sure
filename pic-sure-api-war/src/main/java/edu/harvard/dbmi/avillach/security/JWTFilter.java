package edu.harvard.dbmi.avillach.security;

import java.io.IOException;
import java.util.Base64;

import javax.annotation.Resource;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.data.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

@Provider
public class JWTFilter implements ContainerRequestFilter {

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

			Jws<Claims> jws = Jwts.parser().setSigningKey(Base64.getEncoder().encode(clientSecret.getBytes())).parseClaimsJws(token);

			String subject = jws.getBody().getSubject();
			
			String userId = jws.getBody().get(userIdClaim, String.class);
						
			User authenticatedUser = userRepo.findOrCreate(subject, userId);
			
			String[] rolesAllowed = resourceInfo.getResourceMethod().isAnnotationPresent(RolesAllowed.class)
					? resourceInfo.getResourceMethod().getAnnotation(RolesAllowed.class).value()
							: new String[]{};
			for(String role : rolesAllowed) {
				if(!authenticatedUser.getRoles().contains(role)) {
					requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity("User has insufficient privileges.").build());
				}
			}
			
		} catch (Exception e) {
			e.printStackTrace();
			requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity("User has insufficient privileges.").build());
		}
	}

}
