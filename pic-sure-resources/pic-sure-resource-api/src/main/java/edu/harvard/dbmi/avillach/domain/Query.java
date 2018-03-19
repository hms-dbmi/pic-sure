package edu.harvard.dbmi.avillach.domain;

import java.util.Map;

public class Query {
	private Map<String, String> resourceCredentials;
	private String query;
	
	public Map<String, String> getResourceCredentials() {
		return resourceCredentials;
	}
	public Query setResourceCredentials(Map<String, String> resourceCredentials) {
		this.resourceCredentials = resourceCredentials;
		return this;
	}
	public String getQuery() {
		return query;
	}
	public Query setQuery(String query) {
		this.query = query;
		return this;
	}
}
