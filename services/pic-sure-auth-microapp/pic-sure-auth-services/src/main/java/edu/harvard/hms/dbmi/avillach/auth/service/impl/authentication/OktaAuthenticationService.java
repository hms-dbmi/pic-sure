package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Objects;

public class OktaAuthenticationService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final String idp_provider_uri;
    private final String clientId;
    private final String spClientSecret;
    private final RestClientUtil restClientUtil;

    public OktaAuthenticationService(String idpProviderUri, String clientId, String spClientSecret, RestClientUtil restClientUtil) {
        this.idp_provider_uri = idpProviderUri;
        this.clientId = clientId;
        this.spClientSecret = spClientSecret;
        this.restClientUtil = restClientUtil;
    }

    /**
     * Exchange the code for an access token. This is a call to the OKTA token endpoint.
     * Documentation: <a href="https://developer.okta.com/docs/reference/api/oidc/#token">Token</a>
     *
     * @param host The UriInfo object from the JAX-RS context
     * @param code    The code to exchange
     * @return The response from the token endpoint as a JsonNode
     */
    protected JsonNode handleCodeTokenExchange(String host, String code) {
        String redirectUri = "https://" + host + "/login/loading";
        String queryString = "grant_type=authorization_code" + "&code=" + code + "&redirect_uri=" + redirectUri;
        String oktaTokenUrl = "https://" + this.idp_provider_uri + "/oauth2/default/v1/token";

        return doOktaRequest(oktaTokenUrl, queryString);
    }

    /**
     * Perform a request to the OKTA API using the provided URL and parameters. The request will be a POST request.
     * It is using Authorization Basic authentication. The client ID and client secret are base64 encoded and sent
     * in the Authorization header.
     *
     * @param requestUrl    The URL to call
     * @param requestParams The parameters to send
     * @return The response from the OKTA API as a JsonNode
     */
    private JsonNode doOktaRequest(String requestUrl, String requestParams) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(this.clientId, this.spClientSecret);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<?> resp;
        JsonNode response = null;
        try {
            resp = this.restClientUtil.retrievePostResponse(requestUrl, headers, requestParams);
            response = new ObjectMapper().readTree(Objects.requireNonNull(resp.getBody()).toString());
        } catch (Exception ex) {
            logger.error("handleCodeTokenExchange() failed to call OKTA token endpoint, {}", ex.getMessage());
        }

        logger.debug("getFENCEAccessToken() finished: {}", response);
        return response;
    }

    /**
     * Introspect the token to get the user's email address. This is a call to the OKTA introspect endpoint.
     * Documentation: <a href="https://developer.okta.com/docs/reference/api/oidc/#introspect">/introspect</a>
     *
     * @param userToken The token to introspect
     * @return The response from the introspect endpoint as a JsonNode
     */
    protected JsonNode introspectToken(JsonNode userToken) {
        if (userToken == null) {
            logger.error("USER TOKEN IS NULL. CODE EXCHANGE FAILED.");
            return null;
        }
        JsonNode accessTokenNode = userToken.get("access_token");
        if (accessTokenNode == null) {
            logger.info("USER TOKEN DOES NOT HAVE ACCESS TOKEN ___ {}", userToken);
            return null;
        }

        return this.introspectToken(accessTokenNode.asText());
    }

    protected JsonNode introspectToken(String accessToken) {
        String oktaIntrospectUrl = "https://" + this.idp_provider_uri + "/oauth2/default/v1/introspect";
        String payload = "token_type_hint=access_token&token=" + accessToken;
        return doOktaRequest(oktaIntrospectUrl, payload);
    }

}