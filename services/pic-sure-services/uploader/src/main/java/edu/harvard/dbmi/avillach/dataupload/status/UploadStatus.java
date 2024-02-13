package edu.harvard.dbmi.avillach.dataupload.status;

import java.util.Arrays;

public enum UploadStatus {
    Uploading, Querying, Uploaded, Error, Unsent, Unknown, Queued;

    public static UploadStatus fromString(String status) {
        return Arrays.stream(UploadStatus.values())
            .filter(v -> v.name().equalsIgnoreCase(status.trim()))
            .findAny()
            .orElse(Unknown);
    }
}
