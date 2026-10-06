package edu.harvard.dbmi.avillach.domain;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The status of a query and how to fetch its result.")
public class QueryStatus {

    @Schema(description = "The status of this query.", requiredMode = Schema.RequiredMode.REQUIRED)
    private PicSureStatus status;

    /**
     * a uuid associated to a Resource in the database
     */
    @Schema(
        description = "Always unset. The application no longer uses resource uuids and nothing sets this field.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    )
    private UUID resourceID;

    @Schema(
        description = "A status string returned by HPDS for this query. The values are the resource's own and not a fixed set.",
        example = "SUCCESS"
    )
    private String resourceStatus;

    @Schema(
        description = "The uuid PIC-SURE assigned to the query. The status, result, signed-url and metadata endpoints take it.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6", requiredMode = Schema.RequiredMode.REQUIRED
    )
    private UUID picsureResultId;

    /**
     * when a resource might generate its own resultId and return it, we can keep it here
     */
    @Schema(
        description = "The id the resource assigned to the result, in whatever form that resource uses.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    )
    private String resourceResultId;

    @Schema(
        description = "Details about the query and its result, keyed by name. `picsureQueryId` is the query's id on the resource, present "
            + "when the query is submitted or its status is read. `queryJson` is the stored request body and `queryResultMetadata` the "
            + "stored result metadata, both present on a metadata read."
    )
    private Map<String, Object> resultMetadata;

    @Schema(description = "The estimated size of the result in bytes, 0 until the result exists.", example = "52428")
    private long sizeInBytes;

    @Schema(description = "When the query was queued, in epoch milliseconds.", example = "1790777100000")
    private long startTime;

    @Schema(description = "How long the query took in milliseconds, 0 until it completes.", example = "8250")
    private long duration;

    @Schema(description = "When the result expires, in epoch milliseconds. No current resource sets it, so it is 0.", example = "0")
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
