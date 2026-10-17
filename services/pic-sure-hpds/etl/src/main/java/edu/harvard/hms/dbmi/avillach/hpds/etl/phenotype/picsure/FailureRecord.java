package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSONL record for failure capture (Splunk/Elastic compatible).
 *
 * @param runId identifier of the load run
 * @param dataset what the failing input belongs to (the file name for CSV input)
 * @param inputFile path of the input file
 * @param recordNumber 1-based CSV record number, when known. In a file that was parsed in parallel chunks it counts from the start of the
 *        chunk: use {@code chunkIndex} and {@code chunkStartOffset} to locate the chunk
 * @param participantId the raw patient id cell, when known
 * @param conceptPath the concept path of the row, when known
 * @param timestampRaw the raw timestamp cell, when relevant
 * @param valueRaw the raw value cell, when relevant
 * @param chunkIndex index of the chunk, when the file was parsed in parallel chunks
 * @param chunkStartOffset byte offset in the file at which that chunk starts
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FailureRecord(
    String runId, String dataset, String inputFile, Long recordNumber, String participantId, Integer patientNum, String conceptPath,
    String timestampRaw, String valueRaw, FailureReason reasonCode, String reasonDetail, Integer chunkIndex, Long chunkStartOffset
) {

    /** A failure that is not tied to a chunk. */
    public FailureRecord(
        String runId, String dataset, String inputFile, Long recordNumber, String participantId, Integer patientNum, String conceptPath,
        String timestampRaw, String valueRaw, FailureReason reasonCode, String reasonDetail
    ) {
        this(
            runId, dataset, inputFile, recordNumber, participantId, patientNum, conceptPath, timestampRaw, valueRaw, reasonCode,
            reasonDetail, null, null
        );
    }
}
