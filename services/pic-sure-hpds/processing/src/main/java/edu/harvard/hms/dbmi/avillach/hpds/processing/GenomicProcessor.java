package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Set;

public interface GenomicProcessor {
    BigInteger getPatientMask(DistributableQuery distributableQuery);

    Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask);

    BigInteger createMaskForPatientSet(Set<Integer> patientSubset);

    Collection<String> getVariantList(DistributableQuery distributableQuery);

    String[] getPatientIds();
}
