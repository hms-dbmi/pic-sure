package edu.harvard.dbmi.avillach.domain;

import java.util.UUID;

public class QueryResults {
	private UUID resourceResultId;
	
	private QueryStatus status;
	
	private byte[] results;
	
	private byte[] resultMetadata;

	public UUID getResourceResultId() {
		return resourceResultId;
	}

	public void setResourceResultId(UUID resourceResultId) {
		this.resourceResultId = resourceResultId;
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
}
