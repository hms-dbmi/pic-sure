package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import io.jsonwebtoken.*;

import javax.annotation.Resource;
import javax.mail.internet.HeaderTokenizer;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;


@Path("/token")
public class TokenService {

    @Resource(mappedName = "java:global/client_secret")
    private String clientSecret;

    @POST
    @Path("/inspect")
    @Consumes("application/json")
    public Response inspectToken(Map<String, String> tokenMap){
        TokenInspection tokenInspection = _inspectToken(tokenMap);
        if (tokenInspection.message != null)
            tokenInspection.responseMap.put("message", tokenInspection.message);

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

            //Essentially we want to return jws.getBody() with an additional active: true field
            tokenInspection.responseMap.put("active", true);
            tokenInspection.responseMap.putAll(jws.getBody());

            return tokenInspection;
        } catch (ExpiredJwtException | UnsupportedJwtException | MalformedJwtException | SignatureException | IllegalArgumentException e) {
            tokenInspection.message = "error: " + e.getMessage();
            return tokenInspection;
        }
    }

    private class TokenInspection {
        Map<String, Object> responseMap = new HashMap<>();
        String message = null;
    }

}
