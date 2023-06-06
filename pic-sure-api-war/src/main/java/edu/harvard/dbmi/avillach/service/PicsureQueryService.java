package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.security.JWTFilter;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.sql.Date;
import java.util.*;

import static edu.harvard.dbmi.avillach.util.Utilities.convertQueryRequestToString;
import static edu.harvard.dbmi.avillach.util.Utilities.getRequestSourceFromHeader;

/**
 * Service handling business logic for queries to resources
 */
public class PicsureQueryService {

	public static final String QUERY_RESULT_METADATA_FIELD = "queryResultMetadata";
	private static final String QUERY_JSON_FIELD = "queryJson";

	private final Logger logger = LoggerFactory.getLogger(PicsureQueryService.class);

	private final static ObjectMapper mapper = new ObjectMapper();

	@Inject
	JWTFilter jwtFilter;

	@Inject
	ResourceRepository resourceRepo;

	@Inject
	QueryRepository queryRepo;

	@Inject
	ResourceWebClient resourceWebClient;

	/**
	 * Executes a query on a PIC-SURE resource and creates a Query entity in the
	 * database for the query.
	 *
	 * @param dataQueryRequest - - {@link QueryRequest} containing resource specific credentials object
	 *                         and resource specific query (could be a string or a json object)
	 * @return {@link QueryStatus}
	 */
	@Transactional
	public QueryStatus query(QueryRequest dataQueryRequest, HttpHeaders headers) {
		if (dataQueryRequest == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		UUID resourceId = dataQueryRequest.getResourceUUID();
		if (resourceId == null){
			throw new ProtocolException(ProtocolException.MISSING_RESOURCE_ID);
		}
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			throw new ProtocolException(ProtocolException.RESOURCE_NOT_FOUND + resourceId.toString());
		}
		if (resource.getResourceRSPath() == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
		}
		if (dataQueryRequest.getResourceCredentials() == null){
			dataQueryRequest.setResourceCredentials(new HashMap<String, String>());
		}

		logger.info("path=/query, requestSource={}, queryRequest={}",
				getRequestSourceFromHeader(headers),
				convertQueryRequestToString(mapper, dataQueryRequest)
		);

		dataQueryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());

		QueryStatus results = resourceWebClient.query(resource.getResourceRSPath(), dataQueryRequest);

		Query queryEntity = new Query();
		queryEntity.setResourceResultId(results.getResourceResultId());
		queryEntity.setResource(resource);
		queryEntity.setStatus(results.getStatus());
		queryEntity.setStartTime(new Date(results.getStartTime()));

		ObjectMapper mapper = new ObjectMapper();
		String queryJson = null;
		if( dataQueryRequest.getQuery() != null) {
			try {
				queryJson = mapper.writeValueAsString( dataQueryRequest);
			} catch (JsonProcessingException e) {
				throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
			}
		}

		queryEntity.setQuery(queryJson);

		if (results.getResultMetadata() != null) {
			try {
				queryEntity.setMetadata(mapper.writeValueAsString(results.getResultMetadata()).getBytes());
			} catch (JsonProcessingException e) {
				logger.warn("Unable to parse metadata ", e);
			}
		}
		queryRepo.persist(queryEntity);

		logger.debug("PicsureQueryService() persisted queryEntity with id: " + queryEntity.getUuid());
		results.setPicsureResultId(queryEntity.getUuid());
		//In cases where there is no resource result id, the picsure result id will stand in
		if (queryEntity.getResourceResultId() == null){
		    results.setResourceResultId(queryEntity.getUuid().toString());
			queryEntity.setResourceResultId(results.getPicsureResultId().toString());
			queryRepo.persist(queryEntity);
		}
		results.setResourceID(resourceId);
		return results;
	}

	/**
	 * Retrieves the {@link QueryStatus} for a given queryId by looking up the target resource
	 * from the database and calling the target resource for an updated status. The Query entities
	 * in the database are updated each time this is called.
	 *
	 * @param queryId - id of targeted resource
	 * @param credentialsQueryRequest - contains resource specific credentials object
	 * @return {@link QueryStatus}
	 */
	@Transactional
	public QueryStatus queryStatus(UUID queryId, QueryRequest credentialsQueryRequest, HttpHeaders headers) {
		if (queryId == null){
			throw new ProtocolException(ProtocolException.MISSING_QUERY_ID);
		}
		Query query = queryRepo.getById(queryId);
		if (query == null){
			throw new ProtocolException(ProtocolException.QUERY_NOT_FOUND + queryId.toString());
		}
		Resource resource = query.getResource();
		if (resource == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE);
		}
		if (resource.getResourceRSPath() == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
		}

		if (credentialsQueryRequest == null){
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		if (credentialsQueryRequest.getResourceCredentials() == null){
			credentialsQueryRequest.setResourceCredentials(new HashMap<>());
		}
		if(resource.getToken()!=null) {
			credentialsQueryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		}

		logger.info("path=/query/{queryId}/status, queryId={}, requestSource={}, queryRequest={}",
				queryId,
				getRequestSourceFromHeader(headers),
				convertQueryRequestToString(mapper, credentialsQueryRequest)
		);

		//Update status on query object
		QueryStatus status = resourceWebClient.queryStatus(resource.getResourceRSPath(), query.getResourceResultId(), credentialsQueryRequest);
		status.setPicsureResultId(queryId);
		query.setStatus(status.getStatus());
		queryRepo.persist(query);
		status.setStartTime(query.getStartTime().getTime());
		status.setResourceID(resource.getUuid());
		return status;
	}

	/**
	 * Streams the result for a given queryId by looking up the target resource
	 * from the database and calling the target resource for a result. The queryStatus
	 * method should be used to verify that the result is available prior to retrieving it.
	 *
	 * @param queryId - id of target resource
	 * @param credentialsQueryRequest - contains resource specific credentials object
	 * @return Response
	 */
	@Transactional
	public Response queryResult(UUID queryId, QueryRequest credentialsQueryRequest, HttpHeaders headers) {
		if (queryId == null){
			throw new ProtocolException(ProtocolException.MISSING_QUERY_ID);
		}
		Query query = queryRepo.getById(queryId);
		if (query == null){
			throw new ProtocolException(ProtocolException.QUERY_NOT_FOUND + queryId.toString());
		}
		Resource resource = query.getResource();
		if (resource == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE);
		}
		if (resource.getResourceRSPath() == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
		}
		if (credentialsQueryRequest == null){
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		if (credentialsQueryRequest.getResourceCredentials() == null){
			credentialsQueryRequest.setResourceCredentials(new HashMap<>());
		}

		logger.info("path=/query/{queryId}/result, resourceId={}, requestSource={}, queryRequest={}",
				queryId,
				getRequestSourceFromHeader(headers),
				convertQueryRequestToString(mapper, credentialsQueryRequest)
		);


		credentialsQueryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		return resourceWebClient.queryResult(resource.getResourceRSPath(), query.getResourceResultId(), credentialsQueryRequest);
	}

	/**
	 * Streams the result for a query by looking up the target resource
	 * from the database and calling the target resource for a result.
	 *
	 * @param queryRequest - contains resource specific credentials object
	 * @return Response
	 */
	@Transactional
	public Response querySync(QueryRequest queryRequest, HttpHeaders headers) {
		if (queryRequest == null){
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		UUID resourceId = queryRequest.getResourceUUID();
		if (resourceId == null){
			throw new ProtocolException(ProtocolException.MISSING_RESOURCE_ID);
		}
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE);
		}

		if (resource.getResourceRSPath() == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
		}

		if (queryRequest.getResourceCredentials() == null){
			queryRequest.setResourceCredentials(new HashMap<>());
		}

		logger.info("path=/query/sync, resourceId={}, requestSource={}, queryRequest={}",
				queryRequest.getResourceUUID(),
				getRequestSourceFromHeader(headers),
				convertQueryRequestToString(mapper, queryRequest)
		);

		Query queryEntity = new Query();
		queryEntity.setResource(resource);
		queryEntity.setStartTime(new Date(Calendar.getInstance().getTime().getTime()));


		String queryJson = null;
		if( queryRequest.getQuery() != null) {
			try {
				ObjectMapper mapper = new ObjectMapper();
				queryJson = mapper.writeValueAsString( queryRequest);
			} catch (JsonProcessingException e) {
				throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
			}
		}

		queryEntity.setQuery(queryJson);
		queryRepo.persist(queryEntity);
		queryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());

		Response syncResponse = resourceWebClient.querySync(resource.getResourceRSPath(), queryRequest);
		String queryMetadata = queryEntity.getUuid().toString(); // if no response ID, use the queryID (maintain behavior)

		if (syncResponse.getHeaders() != null) {
			Object metadataHeader = syncResponse.getHeaders().get(ResourceWebClient.QUERY_METADATA_FIELD);
			if (metadataHeader != null) {
				try {
					if (metadataHeader instanceof List) {
						queryMetadata = ((List)metadataHeader).get(0).toString();
						logger.debug("found List metadata " + queryMetadata);
					} else {
						logger.debug("Header is " + metadataHeader.getClass().getCanonicalName() + "  ::    "  + metadataHeader);
					}
				} catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
					logger.warn("failed to parse Header : ", e);
				}
			}
		}

		queryEntity.setResourceResultId(queryMetadata);
		queryRepo.persist(queryEntity);

		return syncResponse;
	}

    /**
     * @param queryId      The UUID of the query to get metadata about
     * @return a QueryStatus object containing the metadata stored about the given query
     */
	public QueryStatus queryMetadata(UUID queryId, HttpHeaders headers){
        Query query = queryRepo.getById(queryId);
        if (query == null){
			throw new ProtocolException(ProtocolException.QUERY_NOT_FOUND + queryId.toString());
        }

		logger.info("path=/query/{queryId}/metadata, requestSource={}, queryId={}",
				getRequestSourceFromHeader(headers),
				queryId);


		QueryStatus response = new QueryStatus();
        response.setStartTime(query.getStartTime().getTime());
        response.setPicsureResultId(query.getUuid());
        response.setResourceID(query.getResource().getUuid());
        response.setStatus(query.getStatus());
        response.setResourceResultId(query.getResourceResultId());

        Map<String, Object> metadata = new HashMap<String, Object>();
        try {
			metadata.put(QUERY_JSON_FIELD, new ObjectMapper().readValue(query.getQuery(), Object.class));
			metadata.put(QUERY_RESULT_METADATA_FIELD, String.valueOf(query.getMetadata()));
		} catch (JsonProcessingException e) {
			logger.warn("Unable to use object mapper", e);
		}

        response.setResultMetadata(metadata);

        return response;
    }
}

