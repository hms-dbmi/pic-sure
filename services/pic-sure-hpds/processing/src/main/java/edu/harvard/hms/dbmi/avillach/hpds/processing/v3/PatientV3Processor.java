package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Note: This class was copied from {@link edu.harvard.hms.dbmi.avillach.hpds.processing.patient.PatientProcessor} and updated to use new
 * Query entity
 */
@Component
public class PatientV3Processor implements HpdsV3Processor {

    private static final Logger LOG = LoggerFactory.getLogger(PatientV3Processor.class);
    private final QueryExecutor queryExecutor;

    @Autowired
    public PatientV3Processor(QueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    @Override
    public String[] getHeaderRow(Query query) {
        return new String[] {"PATIENT_NUM"};
    }

    @Override
    public void runQuery(Query query, AsyncResult asyncResult) {
        LOG.info("Pulling results for query {}", query.id());
        // floating all this in memory is a bit gross, but the whole list of
        // patient IDs was already there, so I don't feel too bad
        List<String[]> allPatients =
            queryExecutor.getPatientSubsetForQuery(query).stream().map(patient -> new String[] {patient.toString()}).toList();
        LOG.info("Writing results for query {}", query.id());
        asyncResult.appendResults(allPatients);
        LOG.info("Completed query {}", query.id());
    }
}
