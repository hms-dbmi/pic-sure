package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSONL record for failure capture (Splunk/Elastic compatible).
 *
 * @param runId identifier of the load run
 * @param dataset what the failing input belongs to (the file name for CSV input)
 * @param inputFile path of the input file
 * @param recordNumber 1-based CSV record number in the input file, when known
 * @param participantId the raw patient id cell, when known
 * @param conceptPath the concept path of the row, when known
 * @param timestampRaw the raw timestamp cell, when relevant
 * @param valueRaw the raw value cell, when relevant
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FailureRecord(
    String runId, String dataset, String inputFile, Long recordNumber, String participantId, Integer patientNum, String conceptPath,
    String timestampRaw, String valueRaw, FailureReason reasonCode, String reasonDetail
) {
}
