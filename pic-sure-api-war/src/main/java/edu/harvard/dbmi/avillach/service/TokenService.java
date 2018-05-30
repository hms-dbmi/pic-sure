package edu.harvard.dbmi.avillach.service;

import io.jsonwebtoken.*;

import javax.annotation.Resource;
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
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("active", "false");
        try {
            String token = tokenMap.get("token");
            if (token == null){
                return Response.ok(responseMap, MediaType.APPLICATION_JSON_TYPE).build();
            }

            Jws<Claims> jws = Jwts.parser().setSigningKey(Base64.getEncoder().encode(clientSecret.getBytes())).parseClaimsJws(token);

            //Essentially we want to return jws.getBody() with an additional active: true field
            responseMap.put("active", true);
            responseMap.putAll(jws.getBody());

           return Response.ok(responseMap, MediaType.APPLICATION_JSON_TYPE).build();
        } catch (ExpiredJwtException | UnsupportedJwtException | MalformedJwtException | SignatureException | IllegalArgumentException e) {
            e.printStackTrace();
            return Response.ok(responseMap, MediaType.APPLICATION_JSON_TYPE).build();
        }
    }

}
