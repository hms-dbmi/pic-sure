package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GenomicProcessorParentImpl implements GenomicProcessor {

    private static Logger log = LoggerFactory.getLogger(GenomicProcessorParentImpl.class);

    private final List<GenomicProcessor> nodes;


    @Autowired
    public GenomicProcessorParentImpl() {
        // Just for testing, for now, move to a configuration file or something
        String[] paths = new String[] {"/Users/ryan/dev/pic-sure-hpds-test/data/orchestration/1040.20/all/", "/Users/ryan/dev/pic-sure-hpds-test/data/orchestration/1040.22/all/"};
        nodes = List.of(
                new GenomicProcessorNodeImpl(paths[0]),
                new GenomicProcessorNodeImpl(paths[1])
        );
    }

    @Override
    public BigInteger getPatientMask(DistributableQuery distributableQuery) {
        BigInteger patientMask = null;
        for (GenomicProcessor node : nodes) {
            if (patientMask == null) {
                patientMask = node.getPatientMask(distributableQuery);
            } else {
                patientMask = patientMask.or(node.getPatientMask(distributableQuery));
            }
            log.info("Patients: " + node.patientMaskToPatientIdSet(patientMask));
        }
        return patientMask;
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask) {
        return null;
    }

    @Override
    public BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
        return null;
    }

    @Override
    public Collection<String> getVariantList(DistributableQuery distributableQuery) {
        return nodes.parallelStream().flatMap(node ->
                node.getVariantList(distributableQuery).stream()).collect(Collectors.toList()
        );
    }

    @Override
    public String[] getPatientIds() {
        // todo: verify all nodes have the same potients
        return nodes.get(0).getPatientIds();
    }

    @Override
    public Optional<VariantMasks> getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder) {
        for (GenomicProcessor node : nodes) {
            Optional<VariantMasks> masks = node.getMasks(path, variantMasksVariantBucketHolder);
            if (masks.isPresent()) {
                return masks;
            }
        }
        return Optional.empty();
    }
}
