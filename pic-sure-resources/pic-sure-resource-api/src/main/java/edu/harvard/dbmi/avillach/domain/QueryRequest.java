package edu.harvard.dbmi.avillach.domain;

import java.util.Map;

public class QueryRequest {
	private Map<String, String> resourceCredentials;

	//instead of string
	private Object query;
	
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
}
