package edu.harvard.dbmi.avillach.domain;

import java.util.UUID;

public class QueryStatus {
	private PicSureStatus status;
	
	private UUID resourceID;
	
	private String resourceStatus;
	
	private long sizeInBytes;
	
	private long startTime;
	
	private long duration;
	
	private long expiration;
}
