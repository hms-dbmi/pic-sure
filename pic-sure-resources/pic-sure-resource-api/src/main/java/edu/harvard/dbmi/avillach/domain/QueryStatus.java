package edu.harvard.dbmi.avillach.domain;

import java.util.Map;
import java.util.UUID;

import edu.harvard.dbmi.avillach.util.PicSureStatus;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(description = "A query status object")
public class QueryStatus {

	@ApiModelProperty(value = "The status of the query", allowableValues = "PENDING, RUNNING, COMPLETED, FAILED, CANCELED")
	private PicSureStatus status;

	/**
	 * a uuid associated to a Resource in the database
	 */
	@ApiModelProperty(value = "The resource ID")
	private UUID resourceID;

	/**
	 * a status string returned by the resource
	 */
	@ApiModelProperty(value = "The resource status")
	private String resourceStatus;

	/**
	 * when user makes a query, a corresponding Result uuid is generated
	 */
	@ApiModelProperty(value = "The result UUID")
	private UUID picsureResultId;

	/**
	 * when a resource might generate its own resultId and return it,
	 * we can keep it here
	 */
	@ApiModelProperty(value = "The resource result ID")
	private String resourceResultId;

	/**
	 * any metadata will be stored here
	 */
	@ApiModelProperty(value = "The result metadata")
	private Map<String, Object> resultMetadata;

	@ApiModelProperty(value = "The size of the result in bytes")
	private long sizeInBytes;

	@ApiModelProperty(value = "The start time of the query")
	private long startTime;

	@ApiModelProperty(value = "The duration of the query")
	private long duration;

	@ApiModelProperty(value = "The expiration time of the query")
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
