package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This genomic processor assumes child nodes all have the same patient set
 */
public class GenomicProcessorParentImpl implements GenomicProcessor {

    private static Logger log = LoggerFactory.getLogger(GenomicProcessorParentImpl.class);

    private final List<GenomicProcessor> nodes;

    private final LoadingCache<String, Set<String>> infoStoreValuesCache = CacheBuilder.newBuilder().build(new CacheLoader<>() {
        @Override
        public Set<String> load(String conceptPath) {
            return nodes.parallelStream()
                    .map(node -> node.getInfoStoreValues(conceptPath))
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet());
        }
    });

    private final List<InfoColumnMeta> infoColumnsMeta;
    private final List<String> patientIds;
    private final Set<String> infoStoreColumns;

    public GenomicProcessorParentImpl(List<GenomicProcessor> nodes) {
        this.nodes = nodes;

        patientIds = initializePatientIds();
        infoStoreColumns = initializeInfoStoreColumns();
        infoColumnsMeta = initInfoColumnsMeta();
    }

    @Override
    public Mono<BigInteger> getPatientMask(DistributableQuery distributableQuery) {
        Mono<BigInteger> result = Flux.just(nodes.toArray(GenomicProcessor[]::new))
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
    }

    @Override
    public List<String> getPatientIds() {
        return patientIds;
    }

    private List<String> initializePatientIds() {
        List<String> patientIds = Flux.just(nodes.toArray(GenomicProcessor[]::new))
                .flatMap(node -> Mono.fromCallable(node::getPatientIds).subscribeOn(Schedulers.boundedElastic()))
                .reduce((patientIds1, patientIds2) -> {
                    if (patientIds1.size() != patientIds2.size()) {
                        throw new IllegalStateException("Patient lists from partitions do not match");
                    } else {
                        for (int i = 0; i < patientIds1.size(); i++) {
                            if (!patientIds1.get(i).equals(patientIds2.get(i))) {
                                throw new IllegalStateException("Patient lists from partitions do not match");
                            }
                        }
                    }
                    return patientIds1;
                }).block();

        return patientIds;
    }

    @Override
    public Optional<VariantMasks> getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder) {
        // TODO: test. only used in variant explorer
        for (GenomicProcessor node : nodes) {
            Optional<VariantMasks> masks = node.getMasks(path, variantMasksVariantBucketHolder);
            if (masks.isPresent()) {
                return masks;
            }
        }
        return Optional.empty();
    }

    @Override
    public Set<String> getInfoStoreColumns() {
        return infoStoreColumns;
    }

    private Set<String> initializeInfoStoreColumns() {
        return nodes.parallelStream()
                .map(GenomicProcessor::getInfoStoreColumns)
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getInfoStoreValues(String conceptPath) {
        return infoStoreValuesCache.getUnchecked(conceptPath);
    }

    @Override
    public List<InfoColumnMeta> getInfoColumnMeta() {
        return infoColumnsMeta;
    }

    private List<InfoColumnMeta> initInfoColumnsMeta() {
        return nodes.parallelStream()
                .map(GenomicProcessor::getInfoColumnMeta)
                .map(HashSet::new)
                .flatMap(Set::stream)
                .collect(Collectors.toList());
    }
}
