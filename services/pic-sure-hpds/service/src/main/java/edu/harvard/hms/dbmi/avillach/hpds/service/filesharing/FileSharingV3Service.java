package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryV3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public FileSharingV3Service(QueryV3Service queryService, FileSystemV3Service fileWriter, VariantListV3Processor variantListProcessor) {
        this.queryService = queryService;
        this.fileWriter = fileWriter;
        this.variantListProcessor = variantListProcessor;
    }

    public boolean createPhenotypicData(Query query) {
        return createAndWriteData(query, "phenotypic_data.csv");
    }

    public boolean createPatientList(Query query) {
        return createAndWriteData(query, "patients.txt");
    }

    public boolean createGenomicData(Query query) {
        String vcf = variantListProcessor.runVcfExcerptQuery(query, true);
        return fileWriter.writeResultToFile("genomic_data.tsv", vcf, query.picsureId());
    }

    private boolean createAndWriteData(Query query, String fileName) {
        AsyncResult result = queryService.getResultFor(query.id());
        if (result == null || result.getStatus() != AsyncResult.Status.SUCCESS) {
            return false;
        }
        return fileWriter.writeResultToFile(fileName, result, query.picsureId());
    }
}
