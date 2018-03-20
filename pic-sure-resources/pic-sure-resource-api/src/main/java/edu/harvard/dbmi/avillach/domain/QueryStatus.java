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
}
