package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.data.repository.UserRepository;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.dbmi.avillach.util.PicsureNaming;
import io.jsonwebtoken.*;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;


@Path("/token")
public class TokenService {

    Logger logger = LoggerFactory.getLogger(TokenService.class);

    @Resource(mappedName = "java:global/client_secret")
    private String clientSecret;

    @Inject
    UserRepository userRepo;

    @POST
    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_TOKEN_INTROSPECTION)
    @Path("/inspect")
    @Consumes("application/json")
    public Response inspectToken(Map<String, String> tokenMap){
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
        if (token == null){
            tokenInspection.message = "Token not found";
            return tokenInspection;
        }

        Jws<Claims> jws = null;

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

        String subject = jws.getBody().getSubject();

        User user = userRepo.findOrCreate(new User().setSubject(subject).setUserId(subject));
        if (user == null)
            logger.error("Cannot find or create user with subject - "+ subject +" - extracted from token.");
        else
            logger.info("_inspectToken() user with subject - " + subject + " - exists in database");

        //Essentially we want to return jws.getBody() with an additional active: true field
        if (user.getRoles() != null
                && user.getRoles().contains(PicsureNaming.RoleNaming.ROLE_INTROSPECTION_USER))
            tokenInspection.responseMap.put("active", true);

        tokenInspection.responseMap.putAll(jws.getBody());

        logger.info("_inspectToken() Successfully inspect and return response map: "
                + tokenInspection.responseMap.entrySet()
                .stream()
                .map(entry -> entry.getKey() + " - " + entry.getValue())
                .collect(Collectors.joining(", ")));
        return tokenInspection;
    }

    private class TokenInspection {
        Map<String, Object> responseMap = new HashMap<>();
        String message = null;
    }

}
