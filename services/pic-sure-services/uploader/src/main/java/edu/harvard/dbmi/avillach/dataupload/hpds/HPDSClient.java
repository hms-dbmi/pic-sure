package edu.harvard.dbmi.avillach.dataupload.hpds;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Optional;

@Service
public class HPDSClient {

    private static final Logger LOG = LoggerFactory.getLogger(HPDSClient.class);
    private static final String HPDS_URI = "http://hpds:8080/PIC-SURE/";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private HttpClient client;

    @Autowired
    private HttpClientContext context;

    public boolean writeTestData(Query query) {
        return writeData(query, "test_upload");
    }

    public boolean writePhenotypicData(Query query) {
        return writeData(query, "phenotypic");
    }

    public boolean writeGenomicData(Query query) {
        return writeData(query, "genomic");
    }

    public boolean initializeQuery(Query query) {
        QueryRequest req = new GeneralQueryRequest();
        req.setQuery(query);
        String body = createBody(req);

        if (body == null) {
            return false;
        }

        Optional<HttpPost> maybePost = createPost(HPDS_URI + "query/sync", body);

        return maybePost
            .map(this::sendAndVerifyRequest)
            .orElse(false);
    }

    private boolean writeData(Query query, String mode) {
        String body = createBody(query);
        if (body == null) {
            return false;
        }

        Optional<HttpPost> maybePost = createPost(HPDS_URI + "write/" + mode, body);

        return maybePost
            .map(this::sendAndVerifyRequest)
            .orElse(false);
    }

    private Optional<HttpPost> createPost(String uri, String body) {
        HttpPost request = new HttpPost(URI.create(uri));
        try {
            request.setEntity(new StringEntity(body));
        } catch (UnsupportedEncodingException e) {
            LOG.error("Error making request body", e);
            return Optional.empty();
        }
        request.setHeader("Content-Type", "application/json");

        return Optional.of(request);
    }

    private boolean sendAndVerifyRequest(HttpPost request) {
        try {
            HttpResponse response = client.execute(request, context);
            return response.getStatusLine().getStatusCode() == 200;
        } catch (IOException e) {
            LOG.error("Error making request", e);
            return false;
        }
    }

    private String createBody(Object query) {
        try {
            return mapper.writeValueAsString(query);
        } catch (JsonProcessingException e) {
            LOG.error("Error creating request body", e);
            return null;
        }
    }

    public boolean writePatientData(Query query) {
        return writeData(query, "patients");
    }
}
