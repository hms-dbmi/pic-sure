package edu.harvard.dbmi.avillach.domain;

import java.util.Map;

public class QueryRequest {
	private Map<String, String> resourceCredentials;
	private String query;
	
	public Map<String, String> getResourceCredentials() {
		return resourceCredentials;
	}
	public QueryRequest setResourceCredentials(Map<String, String> resourceCredentials) {
		this.resourceCredentials = resourceCredentials;
		return this;
	}
	public String getQuery() {
		return query;
	}
	public QueryRequest setQuery(String query) {
		this.query = query;
		return this;
	}
}
