package edu.harvard.dbmi.avillach.dataupload.status;

public record DataUploadStatuses(UploadStatus genomic, UploadStatus phenotypic, String queryId) {
}
