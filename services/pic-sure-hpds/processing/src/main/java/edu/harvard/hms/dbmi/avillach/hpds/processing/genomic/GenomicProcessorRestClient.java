package edu.harvard.hms.dbmi.avillach.hpds.processing.genomic;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

public class GenomicProcessorRestClient implements GenomicProcessor {

    private final WebClient webClient;

    private static final ParameterizedTypeReference<Set<String>> VARIANT_SET_TYPE_REFERENCE = new ParameterizedTypeReference<>(){};
    private static final ParameterizedTypeReference<List<InfoColumnMeta>> INFO_COLUMNS_META_TYPE_REFERENCE = new ParameterizedTypeReference<>(){};
    private static final ParameterizedTypeReference<List<String>> LIST_OF_STRING_TYPE_REFERENCE = new ParameterizedTypeReference<>(){};
    private static final ParameterizedTypeReference<Set<String>> SET_OF_STRING_TYPE_REFERENCE = new ParameterizedTypeReference<>(){};

    public GenomicProcessorRestClient(String serviceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(serviceUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Mono<VariantMask> getPatientMask(DistributableQuery distributableQuery) {
        Mono<VariantMask> result = webClient.post()
                .uri("/patients")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(distributableQuery), DistributableQuery.class)
                .retrieve()
                .bodyToMono(VariantMask.class);
        return result;
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(VariantMask patientMask) {
        throw new RuntimeException("Not Implemented");
    }

    @Override
    public VariantMask createMaskForPatientSet(Set<Integer> patientSubset) {
        throw new RuntimeException("Not Implemented");
    }

    @SuppressWarnings("unchecked")
    @Override
    public Mono<Set<String>> getVariantList(DistributableQuery distributableQuery) {
        Mono<Set<String>> result = webClient.post()
                .uri("/variants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(distributableQuery), DistributableQuery.class)
                .retrieve()
                .bodyToMono(VARIANT_SET_TYPE_REFERENCE);
        return result;
    }

    @Override
    public List<String> getPatientIds() {
        List<String> result = webClient.get()
                .uri("/patients/ids")
                .retrieve()
                .bodyToMono(LIST_OF_STRING_TYPE_REFERENCE)
                .block();
        return result;
    }

    @Override
    public Optional<VariableVariantMasks> getMasks(String path, VariantBucketHolder<VariableVariantMasks> variantMasksVariantBucketHolder) {
        throw new RuntimeException("Not Implemented");
    }

    @Override
    public Set<String> getInfoStoreColumns() {
        Set<String> result = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/info/columns")
                        .build())
                .retrieve()
                .bodyToMono(SET_OF_STRING_TYPE_REFERENCE)
                .block();
        return result;
    }

    @Override
    public Set<String> getInfoStoreValues(String conceptPath) {
        Set<String> result = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/info/values")
                        .queryParam("conceptPath", conceptPath)
                        .build(conceptPath))
                .retrieve()
                .bodyToMono(SET_OF_STRING_TYPE_REFERENCE)
                .block();
        return result;
    }

    @Override
    public List<InfoColumnMeta> getInfoColumnMeta() {
        List<InfoColumnMeta> result = webClient.get()
                .uri("/info/meta")
                .retrieve()
                .bodyToMono(INFO_COLUMNS_META_TYPE_REFERENCE)
                .block();
        return result;
    }

    @Override
    public Map<String, Set<String>> getVariantMetadata(Collection<String> variantList) {
        throw new RuntimeException("Not implemented yet");
    }
}
