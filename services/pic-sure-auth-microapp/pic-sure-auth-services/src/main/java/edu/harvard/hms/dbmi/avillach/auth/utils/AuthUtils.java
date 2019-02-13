package edu.harvard.hms.dbmi.avillach.auth.utils;

import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import io.jsonwebtoken.*;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.NotAuthorizedException;
import java.io.UnsupportedEncodingException;

public class AuthUtils {

	private static Logger logger = LoggerFactory.getLogger(AuthUtils.class);

	/**
	 * support both Base64 encrypted and non-Base64 encrypted
	 * @param clientSecret
	 * @param token
	 * @return
	 */
	public static Jws<Claims> parseToken(String clientSecret, String token)
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
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
				throw new NotAuthorizedException("encoding is not supported");
			} catch (JwtException | IllegalArgumentException ex) {
				logger.error("parseToken() throws: " + e.getClass().getSimpleName() + ", " + e.getMessage(), ex);
				throw new NotAuthorizedException(ex.getClass().getSimpleName());
			}
		} catch (JwtException | IllegalArgumentException e) {
			logger.error("parseToken() throws: " + e.getClass().getSimpleName() + ", " + e.getMessage(), ex);
			throw new NotAuthorizedException(e.getClass().getSimpleName());
		}

		if (jws == null) {
			logger.error("parseToken() get null for jws body by parsing Token - " + token);
			throw new NotAuthorizedException("please contact admin to see the log");
		}

		return jws;
	}
}
