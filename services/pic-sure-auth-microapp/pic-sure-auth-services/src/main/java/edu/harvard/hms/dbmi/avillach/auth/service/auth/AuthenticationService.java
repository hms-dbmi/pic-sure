package edu.harvard.hms.dbmi.avillach.auth.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.rest.UserService;
import edu.harvard.hms.dbmi.avillach.auth.service.Auth0UserMatchingService;
import edu.harvard.hms.dbmi.avillach.auth.service.MailService;
import edu.harvard.hms.dbmi.avillach.auth.service.TermsOfServiceService;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.apache.http.Header;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

public class AuthenticationService {
    private Logger logger = LoggerFactory.getLogger(AuthenticationService.class);

    @Inject
    Auth0UserMatchingService matchingService;

    @Inject
    UserRepository userRepository;

    @Inject
    RoleRepository roleRepo;

    @Inject
    TermsOfServiceService tosService;

    @Inject
    MailService mailService;

    @Inject
    UserService userService;

    private JsonNode getFENCEUserProfile(String access_token) {
        logger.debug("getFENCEUserProfile() starting");

        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("Authorization", "Bearer " + access_token));

        logger.debug("getFENCEUserProfile() finished, returning user profile");
        return HttpClientUtil.simpleGet(
                "https://staging.datastage.io/user/user",
                JAXRSConfiguration.client,
                JAXRSConfiguration.objectMapper,
                headers.toArray(new Header[headers.size()])
        );
    }

    private JsonNode getFENCEAccessToken(String fence_code) {
        logger.debug("getFENCEAccessToken() starting, using FENCE code");

        List<Header> headers = new ArrayList<>();
        Base64.Encoder encoder = Base64.getEncoder();
        String fence_auth_header = JAXRSConfiguration.fence_client_id+":"+JAXRSConfiguration.fence_client_secret;
        headers.add(new BasicHeader("Authorization",
                "Basic " + encoder.encodeToString(fence_auth_header.getBytes())));
        headers.add(new BasicHeader("Content-type", "application/x-www-form-urlencoded; charset=UTF-8"));

        // Build the request body, as JSON
        StringBuilder query_string = new StringBuilder()
                .append("grant_type").append('=').append("authorization_code").append('&')
                .append("code").append('=').append(fence_code).append('&')
                .append("redirect_uri").append('=').append("https://datastage-i2b2-transmart-stage.aws.dbmi.hms.harvard.edu/psamaui/login/");

        String fence_url_token = "https://staging.datastage.io/user/oauth2/token";

        JsonNode resp = null;
        try {
            resp = HttpClientUtil.simplePost(
                    fence_url_token,
                    new StringEntity(query_string.toString()),
                    JAXRSConfiguration.client,
                    JAXRSConfiguration.objectMapper,
                    headers.toArray(new Header[headers.size()])
            );
        } catch (Exception ex) {
            logger.error("getFENCEAccessToken() failed to call FENCE token service, "+ex.getMessage());
        }
        logger.debug("getFENCEAccessToken() finished.");
        return resp;
    }

    // Get access_token from FENCE, based on the provided `code`
    public Response getFENCEProfile(Map<String, String> authRequest){
        logger.debug("getFENCEProfile() starting...");
        String fence_code  = authRequest.get("code");

        // The response will contain user information, like token, subject and email
        HashMap<String, String> responseMap = new HashMap<String, String>();

        JsonNode fence_user_profile = null;
        // Get the Gen3/FENCE user profile. It is a JsonNode object
        try {
            logger.debug("getFENCEProfile() query FENCE for user profile with code");
            fence_user_profile = getFENCEUserProfile(getFENCEAccessToken(fence_code).get("access_token").asText());
            logger.debug("getFENCEProfile() user profile structure:"+fence_user_profile.asText());
            logger.debug("getFENCEProfile() .username:" + fence_user_profile.get("username"));
            logger.debug("getFENCEProfile() .user_id:" + fence_user_profile.get("user_id"));
            logger.debug("getFENCEProfile() .email:" + fence_user_profile.get("email"));
        } catch (Exception ex) {
            logger.error("getFENCEToken() could not retrieve the user profile from the auth provider, because "+ex.getMessage());
            throw new NotAuthorizedException("Could not get the user profile "+
                    "from the Gen3 authentication provider."+ex.getMessage());
        }

        User current_user = null;
        try {
            // Create or retrieve the user profile from our database, based on the the key
            // in the Gen3/FENCE profile
            current_user = userService.createUserFromFENCEProfile(fence_user_profile);
            logger.info("getFENCEProfile() saved details for user with e-mail:"
                    +current_user.getEmail()
                    +" and subject:"
                    +current_user.getSubject());

        } catch (Exception ex) {
            logger.error("getFENCEToken() Could not persist the user information, because "+ex.getMessage());
            throw new NotAuthorizedException("The user details could not be persisted. Please contact the administrator.");
        }

        // Update the user's roles (or create them if none exists)
        //Set<Role> actual_user_roles = u.getRoles();
        Iterator<String> access_role_names = fence_user_profile.get("project_access").fieldNames();
        while (access_role_names.hasNext()) {
            String access_role_name = access_role_names.next();
            logger.debug("getFENCEProfile() AccessRole:"+access_role_name);

                if (userService.upsertRole(current_user, access_role_name, "FENCE role "+access_role_name)) {
                    logger.info("getFENCEProfile() Updated user role. Now it includes `"+access_role_name+"`");
                }

                //Application a = new Application();
                //a.setName('FENCE');
                //Privilege p = new Privilege();
                //p.setName("FENCE_");
                //p.setApplication(a);
                //r.setPrivileges(p);

                JsonNode role_object = fence_user_profile.get("project_access").get(access_role_name);
                logger.debug("getFENCEProfile() object:"+role_object.asText());
        }

        // Set the expiration date, based on parameters.
        Date expirationDate = new Date(Calendar.getInstance().getTimeInMillis() + JAXRSConfiguration.tokenExpirationTime);

        logger.debug("getFENCEToken() processed FENCE user profile response, convert it to PIC-SURE user stuff");
        responseMap.put("status","ok");
        responseMap.put("token", current_user.getToken());
        responseMap.put("userId", current_user.getUuid().toString());
        responseMap.put("email", current_user.getEmail());
        responseMap.put("message", "User record has been created/update");
        responseMap.put("pic-sure-token", "pic-sure-jwt-token");
        responseMap.put("expirationDate", ZonedDateTime.ofInstant(expirationDate.toInstant(), ZoneOffset.UTC).toString());

        logger.debug("getFENCEToken() finished");
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
