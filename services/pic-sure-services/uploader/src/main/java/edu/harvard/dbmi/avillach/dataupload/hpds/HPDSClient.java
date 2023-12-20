package edu.harvard.dbmi.avillach.dataupload.hpds;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;

@Service
public class HPDSClient {

    private static final Logger LOG = LoggerFactory.getLogger(HPDSClient.class);
    private static final String HPDS_URI = "http://hpds:8080/PIC-SURE/";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private HttpClient client;

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

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(HPDS_URI + "query/sync"))
            .POST(BodyPublishers.ofString(body))
            .setHeader("Content-Type", "application/json")
            .build();

        return sendAndVerifyRequest(request);
    }

    private boolean writeData(Query query, String mode) {
        String body = createBody(query);
        if (body == null) {
            return false;
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(HPDS_URI + "write/" + mode))
            .POST(BodyPublishers.ofString(body))
            .setHeader("Content-Type", "application/json")
            .build();

        return sendAndVerifyRequest(request);
    }

    private boolean sendAndVerifyRequest(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
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
}
