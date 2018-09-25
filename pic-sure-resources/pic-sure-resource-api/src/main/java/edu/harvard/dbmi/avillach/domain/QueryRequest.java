package edu.harvard.dbmi.avillach.domain;

import java.util.Map;
import java.util.UUID;

import io.swagger.annotations.ApiModel;

@ApiModel(description = "resourceCredentials should be a map with the key identifying the resource and the value an authorization" +
		" token for the resource.  The query is a string or object that contains a search term or query")
public class QueryRequest {

	private Map<String, String> resourceCredentials;

	//instead of string
	private Object query;

	private UUID resourceUUID;

	private String targetURL;

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

	public String getTargetURL() {
		return targetURL;
	}

	public void setTargetURL(String targetURL) {
		this.targetURL = targetURL;
	}

}
