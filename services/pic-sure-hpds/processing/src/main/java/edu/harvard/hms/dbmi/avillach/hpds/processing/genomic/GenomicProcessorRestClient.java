package edu.harvard.hms.dbmi.avillach.hpds.processing.genomic;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class GenomicProcessorRestClient implements GenomicProcessor {

    private final WebClient webClient;

    private static final ParameterizedTypeReference<Collection<String>> VARIANT_LIST_TYPE_REFERENCE = new ParameterizedTypeReference<>(){};
    private static final ParameterizedTypeReference<List<InfoColumnMeta>> INFO_COLUMNS_META_TYPE_REFERENCE = new ParameterizedTypeReference<>(){};
    private static final ParameterizedTypeReference<List<String>> LIST_OF_STRING_TYPE_REFERENCE = new ParameterizedTypeReference<>(){};

    public GenomicProcessorRestClient(String serviceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(serviceUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Mono<BigInteger> getPatientMask(DistributableQuery distributableQuery) {
        Mono<BigInteger> result = webClient.post()
                .uri("/patients")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(distributableQuery), DistributableQuery.class)
                .retrieve()
                .bodyToMono(BigInteger.class);
        return result;
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask) {
        throw new RuntimeException("Not Implemented");
    }

    @Override
    public BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
        throw new RuntimeException("Not Implemented");
    }

    @SuppressWarnings("unchecked")
    @Override
    public Mono<Collection<String>> getVariantList(DistributableQuery distributableQuery) {
        Mono<Collection<String>> result = webClient.post()
                .uri("/variants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(distributableQuery), DistributableQuery.class)
                .retrieve()
                .bodyToMono(VARIANT_LIST_TYPE_REFERENCE);
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
    public Optional<VariantMasks> getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder) {
        throw new RuntimeException("Not Implemented");
    }

    @Override
    public List<String> getInfoStoreColumns() {
        List<String> result = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/info/columns")
                        .build())
                .retrieve()
                .bodyToMono(LIST_OF_STRING_TYPE_REFERENCE)
                .block();
        return result;
    }

    @Override
    public List<String> getInfoStoreValues(String conceptPath) {
        List<String> result = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/info/values")
                        .queryParam("conceptPath", conceptPath)
                        .build(conceptPath))
                .retrieve()
                .bodyToMono(LIST_OF_STRING_TYPE_REFERENCE)
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
}
