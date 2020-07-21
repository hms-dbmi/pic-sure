package edu.harvard.hms.dbmi.avillach.auth.utils;

import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.service.TOSService;
import io.jsonwebtoken.*;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.NotAuthorizedException;
import java.io.UnsupportedEncodingException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>Contains common methods for authentication and authorization.</p>
 */
public class AuthUtils {

	private static Logger logger = LoggerFactory.getLogger(AuthUtils.class);

	@Inject
	TOSService tosService;

	/**
	 * support both Base64 encrypted and non-Base64 encrypted
	 * @param clientSecret
	 * @param token
	 * @return
	 */
	public static Jws<Claims> parseToken(@NotNull String clientSecret, String token)
			throws NotAuthorizedException{
		Jws<Claims> jws;

		try {
			jws = Jwts.parser().setSigningKey(clientSecret.getBytes()).parseClaimsJws(token);
		} catch (SignatureException e) {
			try {
				if(JAXRSConfiguration.clientSecretIsBase64.startsWith("true")) {
					// handle if client secret is base64 encoded
					jws = Jwts.parser().setSigningKey(Base64.decodeBase64(clientSecret
							.getBytes("UTF-8")))
							.parseClaimsJws(token);
				} else {
					// handle if client secret is not base64 encoded
					jws = Jwts.parser().setSigningKey(clientSecret
							.getBytes("UTF-8"))
							.parseClaimsJws(token);
				} 
			} catch (UnsupportedEncodingException ex){
				logger.error("parseToken() clientSecret encoding UTF-8 is not supported. "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				throw new NotAuthorizedException("encoding is not supported");
			} catch (JwtException | IllegalArgumentException ex) {
				logger.error("parseToken() throws: " + e.getClass().getSimpleName() + ", " + e.getMessage());
				throw new NotAuthorizedException(ex.getClass().getSimpleName());
			}
		} catch (JwtException | IllegalArgumentException e) {
			logger.error("parseToken() throws: " + e.getClass().getSimpleName() + ", " + e.getMessage());
			throw new NotAuthorizedException(e.getClass().getSimpleName());
		}

		if (jws == null) {
			logger.error("parseToken() get null for jws body by parsing Token - " + token);
			throw new NotAuthorizedException("please contact admin to see the log");
		}

		return jws;
	}

	/*
	 * Generate a HashMap of all the information used in the JSON response back to the UI client, while also
	 * package the same information inside a valid PSAMA JWT token
	 *
	 */
	public HashMap<String, String> getUserProfileResponse(Map<String, Object> claims) {
		logger.debug("getUserProfileResponse() starting...");

		HashMap<String, String> responseMap = new HashMap<String, String>();
		logger.debug("getUserProfileResponse() initialized map");

		logger.debug("getUserProfileResponse() using claims:"+claims.toString());

		String token = JWTUtil.createJwtToken(
				JAXRSConfiguration.clientSecret,
				"whatever",
				"edu.harvard.hms.dbmi.psama",
				claims,
				claims.get("sub").toString(),
				JAXRSConfiguration.tokenExpirationTime
		);
		logger.debug("getUserProfileResponse() PSAMA JWT token has been generated. Token:"+token);
		responseMap.put("token", token);

		logger.debug("getUserProfileResponse() .usedId field is set");
		responseMap.put("userId", claims.get("sub").toString());

		logger.debug("getUserProfileResponse() .email field is set");
		responseMap.put("email", claims.get("email").toString());

		logger.debug("getUserProfileResponse() acceptedTOS is set");

		logger.info("starting debug");
		
		logger.info(tosService.toString());
		logger.info(tosService.getLatest());
		logger.info(claims.get("sub").toString());
		logger.info(tosService.hasUserAcceptedLatest(claims.get("sub").toString()));

		boolean acceptedTOS = JAXRSConfiguration.tosEnabled.startsWith("true") ?
				tosService.getLatest() == null || tosService.hasUserAcceptedLatest(claims.get("sub").toString()) : true;

		responseMap.put("acceptedTOS", ""+acceptedTOS);

		logger.debug("getUserProfileResponse() expirationDate is set");
		Date expirationDate = new Date(Calendar.getInstance().getTimeInMillis() + JAXRSConfiguration.tokenExpirationTime);
		responseMap.put("expirationDate", ZonedDateTime.ofInstant(expirationDate.toInstant(), ZoneOffset.UTC).toString());

		logger.debug("getUserProfileResponse() finished");
		return responseMap;
	}



}
