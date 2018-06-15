package edu.harvard.dbmi.avillach.domain;

import java.util.Map;
import java.util.UUID;

public class QueryRequest {
	private Map<String, String> resourceCredentials;

	//instead of string
	private Object query;

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
