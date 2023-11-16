package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantListProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Used for sharing data. Given a query, this service will write
 * phenotypic and genomic data into a directory
 */
@Service
public class FileSharingService {

    private static final Logger LOG = LoggerFactory.getLogger(FileSharingService.class);

    @Autowired
    private QueryService queryService;

    @Autowired
    private FileSystemService fileWriter;

    @Autowired
    private VariantListProcessor variantListProcessor;

    public boolean createPhenotypicData(Query query) {
        AsyncResult result = queryService.getResultFor(query.getId());
        if (result == null || result.getStatus() != AsyncResult.Status.SUCCESS) {
            return false;
        }
        return fileWriter.writeResultToFile("phenotypic_data.csv", result, query.getPicSureId());
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
}
