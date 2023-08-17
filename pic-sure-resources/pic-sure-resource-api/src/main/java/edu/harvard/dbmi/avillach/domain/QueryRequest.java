package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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

@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryRequest {

	@Schema(hidden = true)
	private Map<String, String> resourceCredentials = new HashMap<>();

	@Schema(hidden = true)
	private Object query;

	@Schema(hidden = true)
	private UUID resourceUUID;

	@Schema(hidden = true)
	private UUID commonAreaUUID;

	public Map<String, String> getResourceCredentials() {
		return resourceCredentials;
	}
	public QueryRequest setResourceCredentials(Map<String, String> resourceCredentials) {
		this.resourceCredentials = resourceCredentials;
		return this;
	}

	public Object getQuery() {
		return query;
	}
	public QueryRequest setQuery(Object query) {
		this.query = query;
		return this;
	}

	public UUID getResourceUUID() {
		return resourceUUID;
	}

	public void setResourceUUID(UUID resourceUUID) {
		this.resourceUUID = resourceUUID;
	}

	public UUID getCommonAreaUUID() {
		return commonAreaUUID;
	}

	public void setCommonAreaUUID(UUID commonAreaUUID) {
		this.commonAreaUUID = commonAreaUUID;
	}
}
