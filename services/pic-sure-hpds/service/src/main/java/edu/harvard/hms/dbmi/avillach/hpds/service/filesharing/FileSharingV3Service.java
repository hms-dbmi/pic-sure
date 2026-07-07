package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryV3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Used for sharing data. Given a query, this service will write phenotypic and genomic data into a directory
 *
 * Note: This class was copied from {@link edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingService} and updated to use new
 * Query entity
 */
@Service
public class FileSharingV3Service {

    private static final Logger LOG = LoggerFactory.getLogger(FileSharingV3Service.class);

    private final QueryV3Service queryService;
    private final FileSystemV3Service fileWriter;
    private final VariantListV3Processor variantListProcessor;
    private final LoggingClient loggingClient;

    public FileSharingV3Service(QueryV3Service queryService, FileSystemV3Service fileWriter, VariantListV3Processor variantListProcessor, LoggingClient loggingClient) {
        this.queryService = queryService;
        this.fileWriter = fileWriter;
        this.variantListProcessor = variantListProcessor;
        this.loggingClient = loggingClient;
    }

    public boolean createPhenotypicData(Query query) {
        boolean success = createAndWriteData(query, "phenotypic_data.csv");
        if (success) {
            sendFileWrittenEvent(query.id().toString(), "phenotypic");
        }
        return success;
    }

    public boolean createPatientList(Query query) {
        boolean success = createAndWriteData(query, "patients.txt");
        if (success) {
            sendFileWrittenEvent(query.id().toString(), "patients");
        }
        return success;
    }

    public boolean createGenomicData(Query query) {
        String vcf = variantListProcessor.runVcfExcerptQuery(query, true);
        boolean success = fileWriter.writeResultToFile("genomic_data.tsv", vcf, query.picsureId().toString());
        if (success) {
            sendFileWrittenEvent(query.id().toString(), "genomic");
        }
        return success;
    }

    private void sendFileWrittenEvent(String queryId, String dataType) {
        if (loggingClient != null && loggingClient.isEnabled()) {
            try {
                loggingClient.send(LoggingEvent.builder("DATA_ACCESS")
                    .action("data.file.written")
                    .metadata(Map.of(
                        "query_id", queryId,
                        "data_type", dataType
                    ))
                    .build());
            } catch (Exception e) {
                LOG.warn("Failed to send audit log event", e);
            }
        }
    }

    private boolean createAndWriteData(Query query, String fileName) {
        AsyncResult result = queryService.getResultFor(query.id());
        if (result == null || result.getStatus() != AsyncResult.Status.SUCCESS) {
            return false;
        }
        return fileWriter.writeResultToFile(fileName, result, query.picsureId().toString());
    }
}
