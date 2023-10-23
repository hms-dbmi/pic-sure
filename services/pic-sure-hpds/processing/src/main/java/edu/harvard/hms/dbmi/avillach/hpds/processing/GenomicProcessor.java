package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Set;

public interface GenomicProcessor {
    BigInteger getPatientMaskForVariantInfoFilters(DistributableQuery distributableQuery);

    Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask);

    BigInteger createMaskForPatientSet(Set<Integer> patientSubset);

    Collection<String> processVariantList(Set<Integer> patientSubsetForQuery, Query query);

    String[] getPatientIds();
}
