package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.util.Utilities;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.UUID;

public class FormatService {

    private final Logger logger = LoggerFactory.getLogger(FormatService.class);

    private final static ObjectMapper mapper = new ObjectMapper();

    @Inject
    ResourceRepository resourceRepo;

    @Inject
    ResourceWebClient resourceWebClient;

    public Response format(QueryRequest credentialsQueryRequest, HttpHeaders headers) {
        Resource resource = resourceRepo.getById(credentialsQueryRequest.getResourceUUID());
        if (resource == null){
            throw new ProtocolException(ProtocolException.RESOURCE_NOT_FOUND + credentialsQueryRequest.getResourceUUID().toString());
        }

        if (resource.getResourceRSPath() == null){
            throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
        }
        if (credentialsQueryRequest == null){
            credentialsQueryRequest = new QueryRequest();
        }

        if (credentialsQueryRequest.getResourceCredentials() == null) {
            credentialsQueryRequest.setResourceCredentials(new HashMap<String, String>());
        }

        String requestSourceFromHeader = Utilities.getRequestSourceFromHeader(headers);
        logger.info("path=/info/{resourceId}, resourceId={}, requestSource={}, credentialsQueryRequest={}",
                credentialsQueryRequest.getResourceUUID().toString(),
                requestSourceFromHeader,
                Utilities.convertQueryRequestToString(mapper, credentialsQueryRequest)
        );

        return resourceWebClient.queryContinuous(resource.getResourceRSPath(), credentialsQueryRequest, requestSourceFromHeader);
    }

}
