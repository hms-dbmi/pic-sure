package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.data.entity.User;
import edu.harvard.dbmi.avillach.service.HttpClientUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.http.HttpResponse;
import org.junit.BeforeClass;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class BaseIT {

    protected static String endpointUrl;
    protected final static ObjectMapper json = new ObjectMapper();
    protected final static String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
    protected static final String IRCT_BEARER_TOKEN_KEY = "IRCT_BEARER_TOKEN";


    @BeforeClass
    public static void beforeClass()throws Exception {
        endpointUrl = System.getProperty("service.url");
    }

    public static String generateJwtForSystemUser() {
        return Jwts.builder()
                .setSubject("samlp|foo@bar.com")
                .setIssuer("http://localhost:8080")
                .setIssuedAt(new Date()).addClaims(Map.of("email","foo@bar.com"))
                .setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
                .signWith(SignatureAlgorithm.HS512, "foo".getBytes())
                .compact();
    }

    public String generateJwtForNonSystemUser() {
        return Jwts.builder()
                .setSubject("samlp|foo2@bar.com")
                .setIssuer("http://localhost:8080")
                .setIssuedAt(new Date()).addClaims(Map.of("email","foo2@bar.com"))
                .setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
                .signWith(SignatureAlgorithm.HS512, "foo".getBytes())
                .compact();
    }

    public String generateJwtForCallingTokenInspection() {
        return Jwts.builder()
                .setSubject("samlp|foo3@bar.com")
                .setIssuer("http://localhost:8080")
                .setIssuedAt(new Date()).addClaims(Map.of("email","foo3@bar.com"))
                .setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
                .signWith(SignatureAlgorithm.HS512, "foo".getBytes())
                .compact();
    }

    public String generateJwtForTokenInspectionUser() {
        return Jwts.builder()
                .setSubject("samlp|foo4@bar.com")
                .setIssuer("http://localhost:8080")
                .setIssuedAt(new Date()).addClaims(Map.of("email","foo4@bar.com"))
                .setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
                .signWith(SignatureAlgorithm.HS512, "foo".getBytes())
                .compact();
    }

    public String generateExpiredJwt() {
        return Jwts.builder()
                .setSubject("samlp|foo@bar.com")
                .setIssuer("http://localhost:8080")
                .setIssuedAt(new Date()).addClaims(Map.of("email","foo@bar.com"))
                .setExpiration(Date.from(LocalDateTime.now().minusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
                .signWith(SignatureAlgorithm.HS512, "foo".getBytes())
                .compact();
    }

   /* public User createUserWithRole(PicsureWarNaming.RoleNaming role){
        User user = new User();
        List<User> usersToAdd = new ArrayList<>();
        usersToAdd.add(user);

        HttpResponse response = HttpClientUtil.retrievePostResponse(endpointUrl + "/token/inspect", headers, tokenString);

    }*/
}
