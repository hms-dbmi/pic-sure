package edu.harvard.dbmi.avillach.dataupload.hpds;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String HPDS_URI = "http://hpds:8080/PIC-SURE/write/";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient client = HttpClient.newHttpClient();

    public boolean writePhenotypicData(Query query) {
        return writeData(query, "phenotypic");
    }

    public boolean writeGenomicData(Query query) {
        return writeData(query, "genomic");
    }

    private boolean writeData(Query query, String mode) {
        String body = createBody(query);
        if (body == null) {
            return false;
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(HPDS_URI + mode))
            .POST(BodyPublishers.ofString(body))
            .setHeader("Content-Type", "application/json")
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            LOG.error("Error making request", e);
            return false;
        }
    }

    private String createBody(Query query) {
        try {
            return mapper.writeValueAsString(query);
        } catch (JsonProcessingException e) {
            LOG.error("Error creating request body", e);
            return null;
        }
    }
}
