package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Sends the audit event that records a file written to a query's shared directory. {@link FileSharingService} and
 * {@link FileSharingV3Service} both report through it, so the event has one shape whichever query model wrote the file.
 */
final class FileWrittenAudit {

    private static final Logger LOG = LoggerFactory.getLogger(FileWrittenAudit.class);

    private FileWrittenAudit() {}

    /**
     * Sends a {@code DATA_ACCESS} event with the action {@code data.file.written}. Sends nothing when there is no logging client or the
     * client is disabled. A failure to send is logged and never reaches the caller, so it cannot fail the write it reports.
     *
     * @param loggingClient the client to send through, or null when the service has none
     * @param queryId the id the event names the query by
     * @param dataType the kind of file written: {@code phenotypic}, {@code patients} or {@code genomic}
     */
    static void send(LoggingClient loggingClient, String queryId, String dataType) {
        if (loggingClient != null && loggingClient.isEnabled()) {
            try {
                loggingClient.send(
                    LoggingEvent.builder("DATA_ACCESS").action("data.file.written")
                        .metadata(Map.of("query_id", queryId, "data_type", dataType)).build()
                );
            } catch (Exception e) {
                LOG.warn("Failed to send audit log event", e);
            }
        }
    }
}
