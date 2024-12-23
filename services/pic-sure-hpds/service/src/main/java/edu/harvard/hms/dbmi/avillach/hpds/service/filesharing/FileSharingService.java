package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantListProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

    public FileSharingService(
        QueryService queryService, FileSystemService fileWriter,
        VariantListProcessor variantListProcessor
    ) {
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
        try {
            String vcf = variantListProcessor.runVcfExcerptQuery(query, true);
            return fileWriter.writeResultToFile("genomic_data.tsv", vcf, query.getPicSureId());
        } catch (IOException e) {
            LOG.error("Error running genomic query", e);
            return false;
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
