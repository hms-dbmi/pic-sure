package edu.harvard.dbmi.avillach.domain;

import java.util.UUID;

public class QueryResults {
	private UUID resultId;

	//TODO This needs to be different somehow
	private String resourceResultId;
	
	private QueryStatus status;
	
	private byte[] results;
	
	private byte[] resultMetadata;

	public UUID getResultId() {
		return resultId;
	}

	public void setResultId(UUID resultId) {
		this.resultId = resultId;
	}

	public QueryStatus getStatus() {
		return status;
	}

	public void setStatus(QueryStatus status) {
		this.status = status;
	}

	public byte[] getResults() {
		return results;
	}

	public void setResults(byte[] results) {
		this.results = results;
	}

	public byte[] getResultMetadata() {
		return resultMetadata;
	}

	public void setResultMetadata(byte[] resultMetadata) {
		this.resultMetadata = resultMetadata;
	}

	public String getResourceResultId() {
		return resourceResultId;
	}

	public void setResourceResultId(String resourceResultId) {
		this.resourceResultId = resourceResultId;
	}
}
