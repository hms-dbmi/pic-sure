package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static edu.harvard.dbmi.avillach.service.HttpClientUtil.composeURL;
import static edu.harvard.dbmi.avillach.service.HttpClientUtil.retrievePostResponse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class PicsureSearchServiceIT extends BaseIT{

    @Test
    public void testSearch() throws Exception {
        //We don't know what resource we fetched, so we don't want to actually try to reach it
        //Result to return
        HashMap<String, String> anyOldResult = new HashMap<>();

        wireMockRule.stubFor(any(anyUrl())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(objectMapper.writeValueAsString(anyOldResult))));


        String uri = composeURL(endpointUrl, "/search/"+resourceId);
        QueryRequest searchQueryRequest = new QueryRequest();
        HttpResponse response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(searchQueryRequest));
        assertEquals("Missing query request info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        Map<String, String> clientCredentials = new HashMap<String, String>();
        //TODO This needs to not assume which resource is being used/what the token is... what to do!!... maybe we don't need to test this because it's tested by individual resourceRs tests?
        clientCredentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "testToken");
        searchQueryRequest.setResourceCredentials(clientCredentials);
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(searchQueryRequest));
        assertEquals("Missing query search info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        searchQueryRequest.setQuery("blood");
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(searchQueryRequest));
        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());

        SearchResults results = objectMapper.readValue(response.getEntity().getContent(), SearchResults.class);
        assertNotNull("Results should not be null", results.getResults());
        assertEquals("Searchquery should match input query", results.getSearchQuery(), "blood");

    }
}
