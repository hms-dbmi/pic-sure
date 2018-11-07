package edu.harvard.hms.dbmi.avillach.auth.rest;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.PicsureNaming;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import io.jsonwebtoken.*;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;


@Path("/token")
public class TokenService {

	private Logger logger = LoggerFactory.getLogger(TokenService.class);

	@Resource(mappedName = "java:global/client_secret")
	private String clientSecret;

	@Resource(mappedName = "java:global/auth0token")
	private String auth0token;

	@Resource(mappedName = "java:global/auth0host")
	private String auth0host;

	@Inject
	UserRepository userRepo;

	@POST
	@RolesAllowed(AuthNaming.AuthRoleNaming.ROLE_TOKEN_INTROSPECTION)
	@Path("/inspect")
	@Consumes("application/json")
	public Response inspectToken(Map<String, String> tokenMap,
			@QueryParam(value = "email") String email){
		logger.info("TokenInspect starting...");
		TokenInspection tokenInspection = _inspectToken(tokenMap);
		if (tokenInspection.message != null)
			tokenInspection.responseMap.put("message", tokenInspection.message);

		logger.info("Finished token introspection.");
		return PICSUREResponse.success(tokenInspection.responseMap);
	}

	private TokenInspection _inspectToken(Map<String, String> tokenMap){
		logger.debug("_inspectToken, the incoming token map is: " + tokenMap.entrySet()
		.stream()
		.map(entry -> entry.getKey() + " - " + entry.getValue())
		.collect(Collectors.joining(", ")));

		TokenInspection tokenInspection = new TokenInspection();
		tokenInspection.responseMap.put("active", false);
		String token = tokenMap.get("token");
		logger.debug("getting token: " + token);
		if (token == null || token.isEmpty()){
			logger.error("Token - "+ token + " is blank");
			tokenInspection.message = "Token not found";
			return tokenInspection;
		}

		Jws<Claims> jws = null;

		/**
		 * This parser is taking care of both clientSecret Base64 encryption and non-encryption
		 */
		try {
			jws = Jwts.parser().setSigningKey(clientSecret.getBytes()).parseClaimsJws(token);
		} catch (SignatureException e) {
			try {
				jws = Jwts.parser().setSigningKey(Base64.decodeBase64(clientSecret
						.getBytes("UTF-8")))
						.parseClaimsJws(token);
			} catch (UnsupportedEncodingException ex){
				logger.error("_inspectToken() clientSecret encoding UTF-8 is not supported. "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				throw new ApplicationException("Inner problem: encoding is not supported.");
			} catch (JwtException | IllegalArgumentException ex) {
				logger.error("_inspectToken() throws: " + e.getClass().getSimpleName() + ", " + e.getMessage());
				tokenInspection.message = "error: " + e.getMessage();
				return tokenInspection;
			}
		} catch (JwtException | IllegalArgumentException e) {
			logger.error("_inspectToken() throws: " + e.getClass().getSimpleName() + ", " + e.getMessage());
			tokenInspection.message = "error: " + e.getMessage();
			return tokenInspection;
		}

		if (jws == null) {
			logger.error("_inspectToken() get null for claims by parsing Token - " + token );
			tokenInspection.message = "error: cannot get user info from the token given";
			return tokenInspection;
		}

		String subject = jws.getBody().getSubject();

		User user = new User().setSubject(subject).setSubject(subject);
		user.setEmail(getEmailForSubject(subject));

		try{
			user = userRepo.findByEmail(user.getEmail());
			logger.info("_inspectToken() user with subject - " + subject + " - exists in database");
		} catch (NoResultException e) {
			logger.error("_inspectToken() could not find user with email " + user.getEmail());
			tokenInspection.message = "error: user not authorized";
			return tokenInspection;
		} catch (NonUniqueResultException e) {
			logger.error("_inspectToken() found multiple users with email " + user.getEmail());
			tokenInspection.message = "error: duplicate entries for user";
			return tokenInspection;
		}
		
		//Essentially we want to return jws.getBody() with an additional active: true field
		if (user.getRoles() != null
				&& user.getTotalPrivilege().contains(AuthNaming.AuthRoleNaming.ROLE_INTROSPECTION_USER))
			tokenInspection.responseMap.put("active", true);

		tokenInspection.responseMap.putAll(jws.getBody());

		logger.info("_inspectToken() Successfully inspect and return response map: "
				+ tokenInspection.responseMap.entrySet()
				.stream()
				.map(entry -> entry.getKey() + " - " + entry.getValue())
				.collect(Collectors.joining(", ")));
		return tokenInspection;
	}

	private String getEmailForSubject(String subject) {
		Map<String, String> researchMap = new HashMap<>();
		researchMap.put("user_id", subject);
		String email = null;
		/**
		 * now with a user, we can retrieve email info by subject from Auth0 and set to the user
		 */
		try {
			email = getEmail(researchMap,
					auth0host + "/api/v2/users",
					auth0token);
		} catch (IOException ex){
			logger.error("IOException thrown when retrieving email from Auth0 server");
		}

		if (email==null || email.isEmpty()){
			logger.error("Cannot retrieve email from auth0.");
			return null;
		} else {
			return email;
		}
	}

	private class TokenInspection {
		Map<String, Object> responseMap = new HashMap<>();
		String message = null;
	}

	/**
	 * This method is retrieving email from Auth0 by any specific fields.
	 * Now we only support Auth0 search. If in the future, we want to support
	 * other search methods, it is better to create an interface.
	 *
	 * @return
	 */
	private String getEmail(Map<String, String> searchMap, String auth0host, String token)
			throws IOException {
		String email = null;

		String searchString = "";
		for (Map.Entry<String, String> entry : searchMap.entrySet()){
			searchString += URLEncoder.encode(entry.getKey() +":" + entry.getValue(), "utf-8") + "%20or%20";
		}

		if (searchString.isEmpty()) {
			logger.error("getEmail() no searchString generated." );
			return null;
		}

		searchString = searchString.substring(0, searchString.length()-8);

		String requestPath = "?fields=email&include_fields=true&q=" + searchString;

		String uri = auth0host + requestPath;

		org.apache.http.Header[] headers = new org.apache.http.Header[]{new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)};
		HttpResponse response = HttpClientUtil.retrieveGetResponse(uri, headers);


		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(uri + " did not return a 200: {} {}",response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			//If the result is empty, a 500 is thrown for some reason
			JsonNode responseObject = JAXRSConfiguration.objectMapper.readTree(response.getEntity().getContent());

			if (response.getStatusLine().getStatusCode() == 401) {
				logger.error("Communicating with Auth0 get a 401: " + responseObject + " with URI: " + uri);
			}
			logger.error("Error when communicating with Auth0 server" + responseObject + " with URI: " + uri);
			throw new ApplicationException("Inner application error, please contact admin.");
		}

		JsonNode responseJson = JAXRSConfiguration.objectMapper.readTree(response.getEntity().getContent());

		logger.debug("getEmail() response from Auth0 " + JAXRSConfiguration.objectMapper.writeValueAsString(responseJson));

		if (responseJson.isArray() && responseJson.get(0) != null){
			email = responseJson.get(0).get("email").textValue();
		} else {
			logger.error("getEmail() response from Auth0 is not returning an json array");
		}

		return email;
	}

}
