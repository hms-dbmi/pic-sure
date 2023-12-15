package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.processing.genomic.GenomicProcessorRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

public class GenomicProcessorParentImpl implements GenomicProcessor {

    private static Logger log = LoggerFactory.getLogger(GenomicProcessorParentImpl.class);

    private final List<GenomicProcessor> nodes;

    private final LoadingCache<String, List<String>> infoStoreValuesCache = CacheBuilder.newBuilder().build(new CacheLoader<>() {
        @Override
        public List<String> load(String conceptPath) {
            return nodes.parallelStream()
                    .map(node -> node.getInfoStoreValues(conceptPath))
                    .flatMap(List::stream)
                    .sorted(String::compareToIgnoreCase)
                    .collect(Collectors.toList());
        }
    });

    private List<InfoColumnMeta> infoColumnsMeta;

    private List<String> patientIds;

    public GenomicProcessorParentImpl(List<GenomicProcessor> nodes) {
        this.nodes = nodes;
    }

    @Override
    public Mono<BigInteger> getPatientMask(DistributableQuery distributableQuery) {
        Mono<BigInteger> result = Flux.just(nodes.toArray(GenomicProcessor[]::new))
                .publishOn(Schedulers.boundedElastic())
                .flatMap(node -> node.getPatientMask(distributableQuery))
                .reduce(BigInteger::or);
        return result;
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask) {
        Set<Integer> ids = new HashSet<>();
        String bitmaskString = patientMask.toString(2);
        for(int x = 2;x < bitmaskString.length()-2;x++) {
            if('1'==bitmaskString.charAt(x)) {
                String patientId = getPatientIds().get(x-2).trim();
                ids.add(Integer.parseInt(patientId));
            }
        }
        return ids;
    }

    @Override
    public BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Mono<Collection<String>> getVariantList(DistributableQuery distributableQuery) {
        Mono<Collection<String>> result = Flux.just(nodes.toArray(GenomicProcessor[]::new))
                .flatMap(node -> node.getVariantList(distributableQuery))
                .reduce((variantList1, variantList2) -> {
                    List<String> mergedResult = new ArrayList<>(variantList1.size() + variantList2.size());
                    mergedResult.addAll(variantList1);
                    mergedResult.addAll(variantList2);
                    return mergedResult;
                });
        return result;
        /*return nodes.parallelStream().flatMap(node ->
                node.getVariantList(distributableQuery).stream()).collect(Collectors.toList()
        );*/
    }

    @Override
    public List<String> getPatientIds() {
        if (patientIds != null) {
            return patientIds;
        } else {
            // todo: verify all nodes have the same potients
            List<String> result = nodes.get(0).getPatientIds();
            patientIds = result;
            return result;
        }
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

    @Override
    public List<String> getInfoStoreColumns() {
        // todo: cache this
        return nodes.parallelStream()
                .map(GenomicProcessor::getInfoStoreColumns)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getInfoStoreValues(String conceptPath) {
        return infoStoreValuesCache.getUnchecked(conceptPath);
    }

    @Override
    public List<InfoColumnMeta> getInfoColumnMeta() {
        // todo: initialize on startup?
        if (infoColumnsMeta == null) {
            infoColumnsMeta = nodes.get(0).getInfoColumnMeta();
        }
        return infoColumnsMeta;
    }
}
