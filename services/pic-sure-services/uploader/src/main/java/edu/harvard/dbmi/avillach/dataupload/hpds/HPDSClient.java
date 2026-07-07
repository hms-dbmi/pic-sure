package edu.harvard.dbmi.avillach.dataupload.hpds;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.ResultType;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class HPDSClient {

    private static final Logger LOG = LoggerFactory.getLogger(HPDSClient.class);
    private static final String HPDS_URI = "http://hpds:8080/PIC-SURE/";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final RestClient restClient;

    @Autowired
    public HPDSClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public boolean writeTestData(Query query) {
        return writeData(query, "test_upload");
    }

    public boolean writePhenotypicData(Query query) {
        return writeData(query, "phenotypic");
    }

    public boolean writeGenomicData(Query query) {
        return writeData(query, "genomic");
    }

    public boolean writePatientData(Query query) {
        query.setExpectedResultType(ResultType.PATIENTS);
        return writeData(query, "patients");
    }

    public boolean initializeQuery(Query query) {
        QueryRequest req = new GeneralQueryRequest();
        req.setQuery(query);
        String body = createBody(req);

        if (body == null) {
            return false;
        }

        return sendAndVerifyRequest(HPDS_URI + "query/sync", body);
    }

    private boolean writeData(Query query, String mode) {
        String body = createBody(query);
        if (body == null) {
            return false;
        }

        return sendAndVerifyRequest(HPDS_URI + "write/" + mode, body);
    }

    private boolean sendAndVerifyRequest(String uri, String body) {
        try {
            ResponseEntity<Void> response =
                restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
            return response.getStatusCode().value() == 200;
        } catch (RestClientException e) {
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
