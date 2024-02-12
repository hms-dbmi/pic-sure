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

public class GenomicProcessorPatientMergingParentImpl implements GenomicProcessor {

    private static Logger log = LoggerFactory.getLogger(GenomicProcessorPatientMergingParentImpl.class);

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

    private List<InfoColumnMeta> infoColumnsMeta;

    private List<String> patientIds;

    public GenomicProcessorPatientMergingParentImpl(List<GenomicProcessor> nodes) {
        this.nodes = nodes;
    }

    @Override
    public Mono<BigInteger> getPatientMask(DistributableQuery distributableQuery) {
        Mono<BigInteger> result = Flux.just(nodes.toArray(GenomicProcessor[]::new))
                .flatMapSequential(node -> node.getPatientMask(distributableQuery))
                .reduce(this::appendMask);
        return result;
    }

    public BigInteger appendMask(BigInteger mask1, BigInteger mask2) {
        String binaryMask1 = mask1.toString(2);
        String binaryMask2 = mask2.toString(2);
        String appendedString = binaryMask1.substring(0, binaryMask1.length() - 2) +
                binaryMask2.substring(2);
        return new BigInteger(appendedString, 2);
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask) {
        Set<Integer> ids = new HashSet<>();
        String bitmaskString = patientMask.toString(2);
        List<String> patientIds = getPatientIds();
        for(int x = 2;x < bitmaskString.length()-2;x++) {
            if('1'==bitmaskString.charAt(x)) {
                String patientId = patientIds.get(x-2).trim();
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
        if (patientIds != null) {
            return patientIds;
        } else {
            // todo: verify all nodes have distinct patients
            List<String> result = Flux.just(nodes.toArray(GenomicProcessor[]::new))
                    .flatMapSequential(node -> Mono.fromCallable(node::getPatientIds).subscribeOn(Schedulers.boundedElastic()))
                    .reduce((list1, list2) -> {
                        List<String> concatenatedList = new ArrayList<>(list1);
                        concatenatedList.addAll(list2);
                        return concatenatedList;
                    }).block();
            patientIds = result;
            return result;
        }
    }

    @Override
    public Optional<VariantMasks> getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder) {
        // TODO: implement this. only used in variant explorer
        throw new RuntimeException("Method not implemented");
    }

    @Override
    public Set<String> getInfoStoreColumns() {
        // todo: cache this
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
        // todo: initialize on startup?
        if (infoColumnsMeta == null) {
            infoColumnsMeta = nodes.get(0).getInfoColumnMeta();
        }
        return infoColumnsMeta;
    }
}
