package edu.harvard.dbmi.avillach.domain;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.harvard.dbmi.avillach.QueryRequestDeserializer;
import edu.harvard.dbmi.avillach.Views;
import io.swagger.annotations.ApiModel;

@ApiModel(description = "resourceCredentials should be a map with the key identifying the resource and the value an authorization" +
		" token for the resource.  The query is a string or object that contains a search term or query")
//@JsonDeserialize(using = QueryRequestDeserializer.class)
public class QueryRequest {

	private Map<String, String> resourceCredentials;

	//instead of string
	private Object query;

	private UUID resourceUUID;

	private String targetURL;

	@JsonView(Views.Redact.class)
	public Map<String, String> getResourceCredentials() {
		return resourceCredentials;
	}
	public QueryRequest setResourceCredentials(Map<String, String> resourceCredentials) {
		this.resourceCredentials = resourceCredentials;
		return this;
	}
	@JsonView(Views.Default.class)
	public Object getQuery() {
		return query;
	}
	public QueryRequest setQuery(Object query) {
		this.query = query;
		return this;
	}

	@JsonView(Views.Default.class)
	public UUID getResourceUUID() {
		return resourceUUID;
	}

	public void setResourceUUID(UUID resourceUUID) {
		this.resourceUUID = resourceUUID;
	}

	@JsonView(Views.Default.class)
	public String getTargetURL() {
		return targetURL;
	}

	public void setTargetURL(String targetURL) {
		this.targetURL = targetURL;
	}

}
