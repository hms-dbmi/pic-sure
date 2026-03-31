package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantListProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Used for sharing data. Given a query, this service will write
 * phenotypic and genomic data into a directory
 */
@Service
public class FileSharingService {

    private static final Logger LOG = LoggerFactory.getLogger(FileSharingService.class);

    private final QueryService queryService;
    private final FileSystemService fileWriter;
    private final VariantListProcessor variantListProcessor;
    private final LoggingClient loggingClient;

    public FileSharingService(
        QueryService queryService, FileSystemService fileWriter,
        VariantListProcessor variantListProcessor, LoggingClient loggingClient
    ) {
        this.queryService = queryService;
        this.fileWriter = fileWriter;
        this.variantListProcessor = variantListProcessor;
        this.loggingClient = loggingClient;
    }

    public boolean createPhenotypicData(Query query) {
        boolean success = createAndWriteData(query, "phenotypic_data.csv");
        if (success) {
            sendFileWrittenEvent(query.getId(), "phenotypic");
        }
        return success;
    }

    public boolean createPatientList(Query query) {
        boolean success = createAndWriteData(query, "patients.txt");
        if (success) {
            sendFileWrittenEvent(query.getId(), "patients");
        }
        return success;
    }

    public boolean createGenomicData(Query query) {
        try {
            String vcf = variantListProcessor.runVcfExcerptQuery(query, true);
            boolean success = fileWriter.writeResultToFile("genomic_data.tsv", vcf, query.getPicSureId());
            if (success) {
                sendFileWrittenEvent(query.getId(), "genomic");
            }
            return success;
        } catch (IOException e) {
            LOG.error("Error running genomic query", e);
            return false;
        }
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
        AsyncResult result = queryService.getResultFor(query.getId());
        if (result == null || result.getStatus() != AsyncResult.Status.SUCCESS) {
            return false;
        }
        return fileWriter.writeResultToFile(fileName, result, query.getPicSureId());
    }
}
