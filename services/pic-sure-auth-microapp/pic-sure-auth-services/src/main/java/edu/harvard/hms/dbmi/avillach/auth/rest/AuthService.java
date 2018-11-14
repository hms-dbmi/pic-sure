package edu.harvard.hms.dbmi.avillach.auth.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.Auth0UserMatchingService;
import edu.harvard.hms.dbmi.avillach.auth.service.TermsOfServiceService;
import edu.harvard.hms.dbmi.avillach.jwt.JWTUtil;
import org.apache.http.Header;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Map.of;

@Path("/authentication")
@Consumes("application/json")
@Produces("application/json")
public class AuthService {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Inject
    Auth0UserMatchingService matchingService;

    @Inject
    UserRepository userRepository;

    @Inject
    TermsOfServiceService tosService;

    @POST
    @Path("/")
    public Response getToken(Map<String, String> authRequest){
        String code = authRequest.get("code");
        String redirectURI = authRequest.get("redirectURI");

        if (code == null || redirectURI == null || code.isEmpty() || redirectURI.isEmpty())
            throw new ProtocolException("Missing code or redirectURI in request body.");

        JsonNode jsonNode = tradeCode(authRequest.get("code"), authRequest.get("redirectURI"));
        JsonNode accessTokenNode = jsonNode.get("access_token");
        if (accessTokenNode == null){
            logger.error("getToken() Cannot retrieve access_token by tradeCode(), return json response: " + jsonNode.toString());
            throw new ApplicationException("cannot get access token by the provided code and redirectURI. Please contact admin.");
        }

        JsonNode userInfo = retrieveUserInfo(accessTokenNode.asText());
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
                throw new NotAuthorizedException("No user matching user_id " + userId + " present in database");
            }
        }

        String token = JWTUtil.createJwtToken(
                JAXRSConfiguration.clientSecret, null, null,
                generateClaims(userInfo, new String[]{"user_id", "email","name" }),
                userId, -1);

        boolean acceptedTOS = tosService.hasUserAcceptedLatest(user.getSubject());

        return PICSUREResponse.success(of(
                "token", token,
                "name", userInfo.has("name")?userInfo.get("name"):null,
                "email", userInfo.has("email")?userInfo.get("email"):null,
                "userId", user.getUuid(),
                "acceptedTOS", acceptedTOS));
    }

    private JsonNode tradeCode(String code, String redirectURI){

        String auth0ApiUrl = "https://avillachlab.auth0.com/oauth/token";

        Header header = new BasicHeader("Content-Type", MediaType.APPLICATION_JSON);

        StringEntity requestBody = null;
        String bodyString = null;
        try {
             bodyString = JAXRSConfiguration.objectMapper
                    .writeValueAsString(of(
                            "grant_type", "authorization_code",
                            "client_id", JAXRSConfiguration.clientId,
                            "client_secret", JAXRSConfiguration.clientSecret,
                            "code", code,
                            "scope", "admin",
                            "redirect_uri", redirectURI
                    ));
            requestBody = new StringEntity(bodyString);
        } catch (JsonProcessingException | UnsupportedEncodingException ex){
            logger.error("tradeCode() cannot generate the request body based on the code presented, requestBodyString: " + bodyString);
            throw new ApplicationException("Inner problem, please contact system admin and check the server log");
        }

        return HttpClientUtil.simplePost(auth0ApiUrl,requestBody, null, JAXRSConfiguration.objectMapper, header);
    }

    private JsonNode retrieveUserInfo(String accessToken){
        String auth0UserInfoURI = "https://avillachlab.auth0.com/userinfo";
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
