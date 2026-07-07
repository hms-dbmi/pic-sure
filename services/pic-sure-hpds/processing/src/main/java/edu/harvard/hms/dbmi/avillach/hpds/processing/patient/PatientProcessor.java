package edu.harvard.hms.dbmi.avillach.hpds.processing.patient;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.HpdsProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PatientProcessor implements HpdsProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(PatientProcessor.class);
    private final AbstractProcessor abstractProcessor;

    @Autowired
    public PatientProcessor(AbstractProcessor abstractProcessor) {
        this.abstractProcessor = abstractProcessor;
    }

    @Override
    public String[] getHeaderRow(Query query) {
        return new String[]{"PATIENT_NUM"};
    }

    @Override
    public void runQuery(Query query, AsyncResult asyncResult) {
        LOG.info("Pulling results for query {}", query.getId());
        // floating all this in memory is a bit gross, but the whole list of
        // patient IDs was already there, so I don't feel too bad
        List<String[]> allPatients = abstractProcessor.getPatientSubsetForQuery(query).stream()
            .map(patient -> new String[]{patient.toString()})
            .toList();
        LOG.info("Writing results for query {}", query.getId());
        asyncResult.appendResults(allPatients);
        LOG.info("Completed query {}", query.getId());
    }
}
