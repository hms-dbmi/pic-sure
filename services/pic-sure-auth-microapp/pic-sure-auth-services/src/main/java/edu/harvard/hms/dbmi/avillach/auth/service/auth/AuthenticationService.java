package edu.harvard.hms.dbmi.avillach.auth.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.Auth0UserMatchingService;
import edu.harvard.hms.dbmi.avillach.auth.service.MailService;
import edu.harvard.hms.dbmi.avillach.auth.service.TermsOfServiceService;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import net.minidev.json.JSONObject;
import org.apache.http.Header;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.json.*;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
<<<<<<< HEAD
=======
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
>>>>>>> master
import java.util.*;

public class AuthenticationService {
    private Logger logger = LoggerFactory.getLogger(AuthenticationService.class);

    @Inject
    Auth0UserMatchingService matchingService;

    @Inject
    UserRepository userRepository;

    @Inject
    TermsOfServiceService tosService;

    @Inject
    MailService mailService;

    private JsonNode getFENCEUserProfile(String access_token) {
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("Authorization", "Bearer " + access_token));
        return HttpClientUtil.simpleGet(
                "https://staging.datastage.io/user/user",
                JAXRSConfiguration.client,
                JAXRSConfiguration.objectMapper,
                headers.toArray(new Header[headers.size()])
        );
    }

    private JsonNode getFENCEAccessToken(String fence_code) {
        List<Header> headers = new ArrayList<>();
        //headers.add(new BasicHeader("Content-Type", MediaType.APPLICATION_JSON));
        Base64.Encoder encoder = Base64.getEncoder();
        String fence_auth_header = JAXRSConfiguration.fence_client_id+":"+JAXRSConfiguration.fence_client_secret;
        headers.add(new BasicHeader("Authorization", "Basic " +
                encoder.encodeToString(fence_auth_header.getBytes())));

        // Build the request body, as JSON
        JSONObject reqBody = new JSONObject();
        reqBody.put("grant_type", "authorization_code");
        reqBody.put("code", fence_code);
        reqBody.put("redirect_uri", "https://datastage-i2b2-transmart-stage.aws.dbmi.hms.harvard.edu/psamaui/login/");
        logger.debug("getFENCEToken() req body:"+reqBody.toJSONString());

        return HttpClientUtil.simplePost(
                "https://staging.datastage.io/user/oauth2/token",
                new StringEntity(reqBody.toString(), "application/json"),
                JAXRSConfiguration.client,
                JAXRSConfiguration.objectMapper,
                headers.toArray(new Header[headers.size()])
        );

    }

    // Get access_token from FENCE, based on the provided `code`
    public Response getFENCEProfile(Map<String, String> authRequest){
        logger.debug("getFENCEToken() starting...");
        String fence_code  = authRequest.get("code");

        HashMap<String, String> responseMap = new HashMap<String, String>();
        try {
            JsonNode resp = getFENCEUserProfile(getFENCEAccessToken(fence_code).get("access_token").asText());
            responseMap.put("status", "ok");
            responseMap.put("profile", resp.asText());

        } catch (Exception ex) {
            responseMap.put("status", "error");
            responseMap.put("message", ex.getMessage());
        }
        return PICSUREResponse.success(responseMap);
    }

    public Response getToken(Map<String, String> authRequest){
        String accessToken = authRequest.get("access_token");
        String redirectURI = authRequest.get("redirectURI");

        if (accessToken == null || redirectURI == null || accessToken.isEmpty() || redirectURI.isEmpty())
            throw new ProtocolException("Missing accessToken or redirectURI in request body.");

        JsonNode userInfo = retrieveUserInfo(accessToken);
        JsonNode userIdNode = userInfo.get("user_id");
        if (userIdNode == null){
            logger.error("getToken() cannot find user_id by retrieveUserInfo(), return json response: " + userInfo.toString());
            throw new ApplicationException("cannot get sufficient user information. Please contact admin.");
        }
        String userId = userIdNode.asText();

        logger.info("Successfully retrieved userId, " + userId +
                ", from the provided code and redirectURI");

        String connectionId;
        try {
            connectionId = userInfo.get("identities").get(0).get("connection").asText();
        } catch (Exception e){
            logger.error("getToken() cannot find connection_id by retrieveUserInfo(), return json response: " + userInfo.toString());
            throw new ApplicationException("cannot get sufficient user information. Please contact admin.");
        }

        //Do we have this user already?
        User user = userRepository.findBySubjectAndConnection(userId, connectionId);
        if  (user == null){
            //Try to match
            user = matchingService.matchTokenToUser(userInfo);
            if (user == null){
                if (JAXRSConfiguration.deniedEmailEnabled.startsWith("true")) {
                    mailService.sendDeniedAccessEmail(userInfo);
                }
                throw new NotAuthorizedException("No user matching user_id " + userId + " present in database");
            }
        }

        Map<String, Object> claims = generateClaims(userInfo, new String[]{"user_id","name" });
        claims.put("email",user.getEmail());

        Date expirationDate = new Date(Calendar.getInstance().getTimeInMillis() + JAXRSConfiguration.tokenExpirationTime);
        String token = JWTUtil.createJwtToken(
                JAXRSConfiguration.clientSecret, null, null,
                claims,
                userId, JAXRSConfiguration.tokenExpirationTime);

        boolean acceptedTOS = JAXRSConfiguration.tosEnabled.startsWith("true") ? 
        		tosService.getLatest() == null || tosService.hasUserAcceptedLatest(user.getSubject()) : true;

        HashMap<String, String> responseMap = new HashMap<String, String>();
        
        responseMap.put("token", token);
        responseMap.put("name", (userInfo.has("name")?userInfo.get("name").asText():null));
        responseMap.put("email", user.getEmail());
        responseMap.put("userId", user.getUuid().toString());
        responseMap.put("acceptedTOS", ""+acceptedTOS);
        responseMap.put("expirationDate", ZonedDateTime.ofInstant(expirationDate.toInstant(), ZoneOffset.UTC).toString());
        
        return PICSUREResponse.success(responseMap);
    }

    private JsonNode retrieveUserInfo(String accessToken){
        String auth0UserInfoURI = JAXRSConfiguration.auth0host + "/userinfo";
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("Content-Type", MediaType.APPLICATION_JSON));
        headers.add(new BasicHeader("Authorization", "Bearer " + accessToken));
        return HttpClientUtil.simpleGet(auth0UserInfoURI,
                JAXRSConfiguration.client,
                JAXRSConfiguration.objectMapper,
                headers.toArray(new Header[headers.size()]));
    }

    private Map<String, Object> generateClaims(JsonNode userInfo, String... fields){
        Map<String, Object> claims = new HashMap<>();

        for (String field : fields) {
            JsonNode node = userInfo.get(field);
            if (node != null)
                claims.put(field, node.asText());
        }

        return claims;
    }
}
