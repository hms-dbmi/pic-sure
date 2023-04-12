package edu.harvard.dbmi.avillach.domain;

import java.util.Map;
import java.util.UUID;

import edu.harvard.dbmi.avillach.util.PicSureStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A query status object")
public class QueryStatus {

	@Schema(description = "The status of the query", allowableValues = "PENDING, RUNNING, COMPLETED, FAILED, CANCELED")
	private PicSureStatus status;

	/**
	 * a uuid associated to a Resource in the database
	 */
	@Schema(description = "The resource ID")
	private UUID resourceID;

	/**
	 * a status string returned by the resource
	 */
	@Schema(description = "The resource status")
	private String resourceStatus;

	/**
	 * when user makes a query, a corresponding Result uuid is generated
	 */
	@Schema(description = "The result UUID")
	private UUID picsureResultId;

	/**
	 * when a resource might generate its own resultId and return it,
	 * we can keep it here
	 */
	@Schema(description = "The resource result ID")
	private String resourceResultId;

	/**
	 * any metadata will be stored here
	 */
	@Schema(description = "The result metadata")
	private Map<String, Object> resultMetadata;

	@Schema(description = "The size of the result in bytes")
	private long sizeInBytes;

	@Schema(description = "The start time of the query")
	private long startTime;

	@Schema(description = "The duration of the query")
	private long duration;

	@Schema(description = "The expiration time of the query")
	private long expiration;

	public PicSureStatus getStatus() {
		return status;
	}

	public void setStatus(PicSureStatus status) {
		this.status = status;
	}

	public UUID getResourceID() {
		return resourceID;
	}

	public void setResourceID(UUID resourceID) {
		this.resourceID = resourceID;
	}

	public String getResourceStatus() {
		return resourceStatus;
	}

	public void setResourceStatus(String resourceStatus) {
		this.resourceStatus = resourceStatus;
	}

	public long getSizeInBytes() {
		return sizeInBytes;
	}

	public void setSizeInBytes(long sizeInBytes) {
		this.sizeInBytes = sizeInBytes;
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public long getDuration() {
		return duration;
	}

	public void setDuration(long duration) {
		this.duration = duration;
	}

	public long getExpiration() {
		return expiration;
	}

	public void setExpiration(long expiration) {
		this.expiration = expiration;
	}

	public UUID getPicsureResultId() {
		return picsureResultId;
	}

	public void setPicsureResultId(UUID picsureResultId) {
		this.picsureResultId = picsureResultId;
	}

	public String getResourceResultId() {
		return resourceResultId;
	}

	public void setResourceResultId(String resourceResultId) {
		this.resourceResultId = resourceResultId;
	}

	public Map<String, Object> getResultMetadata() {
		return resultMetadata;
	}

	public void setResultMetadata(Map<String, Object> resultMetadata) {
		this.resultMetadata = resultMetadata;
	}
}
