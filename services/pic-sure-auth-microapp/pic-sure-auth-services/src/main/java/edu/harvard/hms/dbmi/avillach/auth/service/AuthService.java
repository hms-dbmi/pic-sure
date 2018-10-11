package edu.harvard.hms.dbmi.avillach.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.HttpClientUtil;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.JwtBuilder;
import org.apache.http.Header;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Map.of;

@Path("/authentication")
@Consumes("application/json")
@Produces("application/json")
public class AuthService {

    private Logger logger = LoggerFactory.getLogger(this.getClass());


    @POST
    @Path("/")
    public Response getToken(Map<String, String> authRequest){
        String code = authRequest.get("code");
        String redirectURI = authRequest.get("redirectURI");

        if (code == null || redirectURI == null || code.isEmpty() || redirectURI.isEmpty())
            throw new ProtocolException("Missing code or redirectURI in request body.");

        JsonNode jsonNode = tradeCode(authRequest.get("code"), authRequest.get("redirectURI"));
        String accessToken = jsonNode.get("access_token").textValue();
        JsonNode userInfo = retrieveUserInfo(accessToken);
        String userId = userInfo.get("user_id").asText();

        return PICSUREResponse.success(of(
                "token", JWTUtil.createJwtToken(
                        JAXRSConfiguration.clientSecret, null, null,
                        of(
                                "user_id", userId,
                            "email", userInfo.get("email").asText(),
                            "name", userInfo.get("name").asText()
                        ),
                        userId, -1)));
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

        return HttpClientUtil.simplePost(auth0ApiUrl,requestBody, JAXRSConfiguration.client, JAXRSConfiguration.objectMapper, header);
    }

    private JsonNode retrieveUserInfo(String accessToken){
        String auth0UserInfoURI = "https://avillachlab.auth0.com/userinfo";
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("Content-Type", MediaType.APPLICATION_JSON));
        headers.add(new BasicHeader("Authorization", "Bearer " + accessToken));
        return HttpClientUtil.simpleGet(auth0UserInfoURI,
                JAXRSConfiguration.client,
                JAXRSConfiguration.objectMapper,
                (Header[]) headers.toArray());
    }
}
