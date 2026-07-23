package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.*;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

public class GenomicProcessorPatientMergingParentImpl implements GenomicProcessor {

    private static Logger log = LoggerFactory.getLogger(GenomicProcessorPatientMergingParentImpl.class);

    private final List<GenomicProcessor> nodes;

    private final LoadingCache<String, Set<String>> infoStoreValuesCache = CacheBuilder.newBuilder().build(new CacheLoader<>() {
        @Override
        public Set<String> load(String conceptPath) {
            return nodes.parallelStream().map(node -> node.getInfoStoreValues(conceptPath)).flatMap(Set::stream)
                .collect(Collectors.toSet());
        }
    });

    private final List<InfoColumnMeta> infoColumnsMeta;

    private final List<String> patientIds;
    private final Set<String> infoStoreColumns;

    public GenomicProcessorPatientMergingParentImpl(List<GenomicProcessor> nodes) {
        this.nodes = nodes;

        patientIds = initializePatientIds();
        infoStoreColumns = initializeInfoStoreColumns();
        infoColumnsMeta = initInfoColumnsMeta();
    }

    private class SizedResult<T> {
        private final T result;
        private final int size;

        public T getResult() {
            return result;
        }

        public int getSize() {
            return size;
        }

        public SizedResult(T result, int size) {
            this.result = result;
            this.size = size;
        }
    }

    @Override
    public Mono<VariantMask> getPatientMask(DistributableQuery distributableQuery) {
        Mono<VariantMask> result = Flux.just(nodes.toArray(GenomicProcessor[]::new)).flatMapSequential(node -> {
            return node.getPatientMask(distributableQuery).map(mask -> {
                return new SizedResult<>(mask, node.getPatientIds().size());
            });
        }).reduce(this::appendMask).map(SizedResult::getResult);
        return result;
    }

    /**
     * A little bit of a hack for now since the masks don't have sizes at this point and they are needed to merge
     */
    private SizedResult<VariantMask> appendMask(SizedResult<VariantMask> mask1, SizedResult<VariantMask> mask2) {
        VariantMask variantMask = VariableVariantMasks.appendMask(mask1.result, mask2.result, mask1.size, mask2.size);
        return new SizedResult<>(variantMask != null ? variantMask : VariantMask.emptyInstance(), mask1.size + mask2.size);
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(VariantMask patientMask) {
        return patientMask.patientMaskToPatientIdSet(getPatientIds());
    }

    @Override
    public VariantMask createMaskForPatientSet(Set<Integer> patientSubset) {
        return nodes.stream().map(node -> new SizedResult<>(node.createMaskForPatientSet(patientSubset), node.getPatientIds().size()))
            .reduce(this::appendMask).map(SizedResult::getResult).orElseGet(VariantMask::emptyInstance);
    }

    @Override
    public Mono<Set<String>> getVariantList(DistributableQuery distributableQuery) {
        Mono<Set<String>> result = Flux.just(nodes.toArray(GenomicProcessor[]::new))
            .flatMap(node -> node.getVariantList(distributableQuery)).reduce((variantList1, variantList2) -> {
                Set<String> mergedResult = new HashSet<>(variantList1.size() + variantList2.size());
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
        List<String> result = Flux.just(nodes.toArray(GenomicProcessor[]::new))
            .flatMapSequential(node -> Mono.fromCallable(node::getPatientIds).subscribeOn(Schedulers.boundedElastic()))
            .reduce((list1, list2) -> {
                List<String> concatenatedList = new ArrayList<>(list1);
                concatenatedList.addAll(list2);
                return concatenatedList;
            }).block();
        Set<String> distinctPatientIds = new HashSet<>(result);
        if (distinctPatientIds.size() != result.size()) {
            log.warn((result.size() - distinctPatientIds.size()) + " duplicate patients found in patient partitions");
        }
        log.info(distinctPatientIds.size() + " patient ids loaded from patient partitions");
        return ImmutableList.copyOf(result);
    }

    @Override
    public Optional<VariableVariantMasks> getMasks(String path, VariantBucketHolder<VariableVariantMasks> variantMasksVariantBucketHolder) {
        return Optional.ofNullable(Flux.just(nodes.toArray(GenomicProcessor[]::new)).flatMapSequential(node -> {
            VariableVariantMasks masks = node.getMasks(path, new VariantBucketHolder<>()).orElseGet(VariableVariantMasks::new);
            return Mono.fromCallable(() -> new SizedResult<>(masks, node.getPatientIds().size())).subscribeOn(Schedulers.boundedElastic());
        }).reduce((masks1, masks2) -> {
            int combinedSize = masks1.size + masks2.size;
            VariableVariantMasks appendedMasks = VariableVariantMasks.append(masks1.result, masks1.size, masks2.result, masks2.size);
            return new SizedResult<>(appendedMasks, combinedSize);
        }).map(SizedResult::getResult).block());
    }

    @Override
    public Set<String> getInfoStoreColumns() {
        return infoStoreColumns;
    }

    private Set<String> initializeInfoStoreColumns() {
        return nodes.parallelStream().map(GenomicProcessor::getInfoStoreColumns).flatMap(Set::stream).collect(Collectors.toSet());
    }

    @Override
    public Set<String> getInfoStoreValues(String conceptPath) {
        return infoStoreValuesCache.getUnchecked(conceptPath);
    }

    @Override
    public List<InfoColumnMeta> getInfoColumnMeta() {
        return infoColumnsMeta;
    }

    @Override
    public Map<String, Set<String>> getVariantMetadata(Collection<String> variantList) {
        return nodes.parallelStream().map(node -> node.getVariantMetadata(variantList)).reduce((p1, p2) -> {
            Map<String, Set<String>> mapCopy = new HashMap<>(p1);

            for (Map.Entry<String, Set<String>> map2Entry : p2.entrySet()) {
                Set<String> metadataValues = new HashSet<>(mapCopy.getOrDefault(map2Entry.getKey(), new HashSet<>()));
                metadataValues.addAll(map2Entry.getValue());
                mapCopy.put(map2Entry.getKey(), metadataValues);
            }
            return mapCopy;
        }).orElseGet(Map::of);
    }

    private List<InfoColumnMeta> initInfoColumnsMeta() {
        return nodes.parallelStream().map(GenomicProcessor::getInfoColumnMeta).map(HashSet::new).flatMap(Set::stream)
            .collect(Collectors.toList());
    }
}
