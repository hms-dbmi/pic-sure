package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.data.repository.UserRepository;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.dbmi.avillach.util.PicsureNaming;
import io.jsonwebtoken.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;


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
        TokenInspection tokenInspection = new TokenInspection();
        tokenInspection.responseMap.put("active", false);
        try {
            String token = tokenMap.get("token");
            if (token == null){
                tokenInspection.message = "Token not found";
                return tokenInspection;
            }

            Jws<Claims> jws = Jwts.parser().setSigningKey(clientSecret.getBytes()).parseClaimsJws(token);

            String subject = jws.getBody().getSubject();

            // the first subject is for finding, second is for creating
            User user = userRepo.findOrCreate(subject, subject);
            if (user == null)
                logger.error("Cannot find or create user with subject - "+ subject +" - extracted from token.");
            else
                logger.info("_inspectToken() user with subject - " + subject + " - exists in database");

            //Essentially we want to return jws.getBody() with an additional active: true field
            if (user.getRoles() != null
                    && user.getRoles().contains(PicsureNaming.RoleNaming.ROLE_INTROSPECTION_USER))
                tokenInspection.responseMap.put("active", true);

            tokenInspection.responseMap.putAll(jws.getBody());

            return tokenInspection;
        } catch (ExpiredJwtException | UnsupportedJwtException | MalformedJwtException | SignatureException | IllegalArgumentException e) {
            logger.error("_inspectToken() throws: " + e.getClass().getSimpleName() + ", " + e.getMessage());
            tokenInspection.message = "error: " + e.getMessage();
            return tokenInspection;
        }
    }

    private class TokenInspection {
        Map<String, Object> responseMap = new HashMap<>();
        String message = null;
    }

}
