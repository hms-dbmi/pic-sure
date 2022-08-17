package edu.harvard.hms.dbmi.avillach.visualization.model.domain;


import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class QueryRequest {

	public QueryRequest() {}

	public QueryRequest(QueryRequest newQueryRequest) {
		this.resourceCredentials = newQueryRequest.getResourceCredentials();
		Query newQuery = new Query(newQueryRequest.getQuery());
		this.query = newQuery;
		this.resourceUUID = newQueryRequest.getResourceUUID();
	}

	private Map<String, String> resourceCredentials = new HashMap<>();

	//instead of string
	private Query query;

	private UUID resourceUUID;

	public Map<String, String> getResourceCredentials() {
		return resourceCredentials;
	}
	public QueryRequest setResourceCredentials(Map<String, String> resourceCredentials) {
		this.resourceCredentials = resourceCredentials;
		return this;
	}

	public Query getQuery() {
		return query;
	}
	public QueryRequest setQuery(Query query) {
		this.query = query;
		return this;
	}

	public UUID getResourceUUID() {
		return resourceUUID;
	}

	public void setResourceUUID(UUID resourceUUID) {
		this.resourceUUID = resourceUUID;
	}

	@Override
	public String toString() {
		return "QueryRequest{" +
				"resourceCredentials=" + resourceCredentials +
				", query=" + query +
				", resourceUUID=" + resourceUUID +
				'}';
	}
}
