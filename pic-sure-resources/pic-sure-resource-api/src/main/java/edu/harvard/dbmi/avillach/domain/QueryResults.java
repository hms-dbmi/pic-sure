package edu.harvard.dbmi.avillach.domain;

import java.util.UUID;

public class QueryResults {
	private UUID resourceResultId;
	
	private QueryStatus status;
	
	private byte[] results;
	
	private byte[] resultMetadata;
}
