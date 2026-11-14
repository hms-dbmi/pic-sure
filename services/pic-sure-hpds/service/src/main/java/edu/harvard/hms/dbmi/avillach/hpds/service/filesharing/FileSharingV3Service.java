package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryV3Service;
import org.springframework.stereotype.Service;

/**
 * Used for sharing data. Given a query, this service will write phenotypic and genomic data into a directory
 *
 * Note: This class was copied from {@link edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingService} and updated to use new
 * Query entity
 */
@Service
public class FileSharingV3Service {

    private final FileSystemV3Service fileWriter;
    private final VariantListV3Processor variantListProcessor;
    private final LoggingClient loggingClient;

    public FileSharingV3Service(FileSystemV3Service fileWriter, VariantListV3Processor variantListProcessor, LoggingClient loggingClient) {
        this.fileWriter = fileWriter;
        this.variantListProcessor = variantListProcessor;
        this.loggingClient = loggingClient;
    }

    /**
     * Writes a finished job's result into the query's shared directory as {@code phenotypic_data.csv}.
     *
     * @param query the query of the write request, which names the shared directory by its {@code picsureId}
     * @param result the job the write request submitted. It is never looked up by key again, so a later submit that takes over the key
     *        cannot change which file is written
     * @return true when the file was written, false when the job has no result or the write failed
     */
    public boolean createPhenotypicData(Query query, AsyncResult result) {
        boolean success = createAndWriteData(query, result, "phenotypic_data.csv");
        if (success) {
            FileWrittenAudit.send(loggingClient, QueryV3Service.resultKeyFor(query).toString(), "phenotypic");
        }
        return success;
    }

    /**
     * Writes a finished job's result into the query's shared directory as {@code patients.txt}.
     *
     * @param query the query of the write request, which names the shared directory by its {@code picsureId}
     * @param result the job the write request submitted. It is never looked up by key again, so a later submit that takes over the key
     *        cannot change which file is written
     * @return true when the file was written, false when the job has no result or the write failed
     */
    public boolean createPatientList(Query query, AsyncResult result) {
        boolean success = createAndWriteData(query, result, "patients.txt");
        if (success) {
            FileWrittenAudit.send(loggingClient, QueryV3Service.resultKeyFor(query).toString(), "patients");
        }
        return success;
    }

    public boolean createGenomicData(Query query) {
        String vcf = variantListProcessor.runVcfExcerptQuery(query, true);
        boolean success = fileWriter.writeResultToFile("genomic_data.tsv", vcf, query.picsureId().toString());
        if (success) {
            FileWrittenAudit.send(loggingClient, QueryV3Service.resultKeyFor(query).toString(), "genomic");
        }
        return success;
    }

    private boolean createAndWriteData(Query query, AsyncResult result, String fileName) {
        if (result.getStatus() != AsyncResult.Status.SUCCESS) {
            return false;
        }
        return fileWriter.writeResultToFile(fileName, result, query.picsureId().toString());
    }
}
