package edu.harvard.dbmi.avillach.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Schema(name = "QueryRequest", description = "Object containing credentials map under 'resourceCredentials' and query" +
		" object under 'query'. The query object expectedResultType can be on of the following " +
		"\"COUNT\", \"CROSS_COUNT\", \"INFO_COLUMN_LISTING\", \"OBSERVATION_COUNT\", \"OBSERVATION_CROSS_COUNT\", \"DATAFRAME\". ",
		example = "{\n" +
				"    \"resourceUUID\": \"<RESOURCE UUID>\",\n" +
				"    \"query\": {\n" +
				"        \"categoryFilters\": {\n" +
				"            \"\\\\demographics\\\\SEX\\\\\": [\n" +
				"                \"female\",\n" +
				"                \"male\"\n" +
				"            ]\n" +
				"        },\n" +
				"        \"numericFilters\": {\n" +
				"            \"\\\\demographics\\\\AGE\\\\\": {\n" +
				"                \"min\": \"0\",\n" +
				"                \"max\": \"85\"\n" +
				"            }\n" +
				"        },\n" +
				"        \"requiredFields\": [],\n" +
				"        \"anyRecordOf\": [\n" +
				"            \"\\\\demographics\\\\RACE\\\\\"\n" +
				"        ],\n" +
				"        \"variantInfoFilters\": [\n" +
				"            {\n" +
				"                \"categoryVariantInfoFilters\": {},\n" +
				"                \"numericVariantInfoFilters\": {}\n" +
				"            }\n" +
				"        ],\n" +
				"        \"expectedResultType\": \"DATAFRAME\",\n" +
				"        \"fields\": []\n" +
				"    }\n" +
				"}")

/*
 * QueryRequests for vanilla GIC PIC-SURE. Includes details about the source
 * of the request, the requester, and any unifying identifiers for sharing
 */
public class FederatedQueryRequest implements QueryRequest {

	@Schema(hidden = true)
	private Map<String, String> resourceCredentials = new HashMap<>();

	@Schema(hidden = true)
	private Object query;

	@Schema(hidden = true)
	private UUID resourceUUID;

	@Schema(hidden = true)
	private UUID commonAreaUUID;

	@Schema(hidden = true)
	private String institutionOfOrigin;

	@Override
	public Map<String, String> getResourceCredentials() {
		return resourceCredentials;
	}

	@Override
	public FederatedQueryRequest setResourceCredentials(Map<String, String> resourceCredentials) {
		this.resourceCredentials = resourceCredentials;
		return this;
	}

	@Override
	public Object getQuery() {
		return query;
	}

	@Override
	public FederatedQueryRequest setQuery(Object query) {
		this.query = query;
		return this;
	}

	@Override
	public UUID getResourceUUID() {
		return resourceUUID;
	}

	@Override
	public void setResourceUUID(UUID resourceUUID) {
		this.resourceUUID = resourceUUID;
	}

	@Override
	public FederatedQueryRequest copy() {
		FederatedQueryRequest request = new FederatedQueryRequest();
		request.setQuery(getQuery());
		request.setResourceCredentials(getResourceCredentials());
		request.setResourceUUID(getResourceUUID());
		request.setCommonAreaUUID(getCommonAreaUUID());
		request.setInstitutionOfOrigin(getInstitutionOfOrigin());

		return request;
	}

	public UUID getCommonAreaUUID() {
		return commonAreaUUID;
	}

	public void setCommonAreaUUID(UUID commonAreaUUID) {
		this.commonAreaUUID = commonAreaUUID;
	}

	public String getInstitutionOfOrigin() {
		return institutionOfOrigin;
	}

	public void setInstitutionOfOrigin(String institutionOfOrigin) {
		this.institutionOfOrigin = institutionOfOrigin;
	}
}
