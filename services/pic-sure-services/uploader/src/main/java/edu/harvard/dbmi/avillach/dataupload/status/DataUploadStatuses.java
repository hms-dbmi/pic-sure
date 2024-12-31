package edu.harvard.dbmi.avillach.dataupload.status;

import jakarta.annotation.Nullable;

import java.time.LocalDate;

public record DataUploadStatuses(
    UploadStatus genomic, UploadStatus phenotypic, UploadStatus patient, UploadStatus query,
    String queryId, @Nullable LocalDate approved, String site
) {
}
