package edu.harvard.dbmi.avillach.domain;

import java.util.UUID;

public class QueryResults {
	private UUID picsureResultId;

	private String resourceResultId;
	
	private QueryStatus status;
	
	private byte[] results;
	
	private byte[] resultMetadata;

	public UUID getPicsureResultId() {
		return picsureResultId;
	}

	public void setPicsureResultId(UUID picsureResultId) {
		this.picsureResultId = picsureResultId;
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
