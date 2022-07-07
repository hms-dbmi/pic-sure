package edu.harvard.hms.dbmi.avillach.hpds.model.domain;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
public class QueryStatus {
	private PicSureStatus status;

	/**
	 * a uuid associated to a Resource in the database
	 */
	private UUID resourceID;
	
	private String resourceStatus;

	/**
	 * when user makes a query, a corresponding Result uuid is generated
	 */
	private UUID picsureResultId;

	/**
	 * when a resource might generate its own resultId and return it,
	 * we can keep it here
	 */
	private String resourceResultId;

	/**
	 * any metadata will be stored here
	 */
	private Map<String, Object> resultMetadata;

	private long sizeInBytes;
	
	private long startTime;
	
	private long duration;
	
	private long expiration;
}
