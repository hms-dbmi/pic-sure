package edu.harvard.dbmi.avillach.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "resourceCredentials should be a map with the key identifying the resource and the value an authorization" +
		" token for the resource.  The query is a string or object that contains a search term or query")
public class QueryRequest {

	@Schema(description = "Map with the key identifying the resource and the value an authorization token for the resource")
	private Map<String, String> resourceCredentials = new HashMap<>();

	@Schema(description = "A string or object that contains a search term or query. Potential Expect Result Type: " +
			"\"COUNT\", \"CROSS_COUNT\", \"INFO_COLUMN_LISTING\", \"OBSERVATION_COUNT\", \"OBSERVATION_CROSS_COUNT\"",
	example = "{" +
			"\"resourceUUID\": \"<Resource UUID>\"," +
			"\"query\": {" +
			"\"categoryFilters\": {}," +
			"\"numericFilters\": {}," +
			"\"requiredFields\": []," +
			"\"anyRecordOf\": []," +
			"\"variantInfoFilters\": [{" +
			"\"categoryVariantInfoFilters\": {}," +
			"\"numericVariantInfoFilters\": {}" +
			"}]," +
			"\"expectedResultType\": \"COUNT\"" +
			"}" +
			"}")
	private Object query;

	@Schema(description = "The UUID of the resource to query")
	private UUID resourceUUID;

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
}
