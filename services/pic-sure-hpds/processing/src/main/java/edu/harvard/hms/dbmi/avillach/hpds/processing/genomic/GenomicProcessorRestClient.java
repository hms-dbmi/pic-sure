package edu.harvard.hms.dbmi.avillach.hpds.processing.genomic;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public class GenomicProcessorRestClient implements GenomicProcessor {

    private final WebClient webClient;

    public GenomicProcessorRestClient(String serviceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(serviceUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public BigInteger getPatientMask(DistributableQuery distributableQuery) {
        return webClient.post()
                .uri("/patients")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(distributableQuery), DistributableQuery.class)
                .retrieve()
                .bodyToMono(BigInteger.class)
                .block();
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask) {
        return null;
    }

    @Override
    public BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Collection<String> getVariantList(DistributableQuery distributableQuery) {
        return webClient.post()
                .uri("/variants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(distributableQuery), DistributableQuery.class)
                .retrieve()
                .bodyToMono(Collection.class)
                .block();
    }

    @Override
    public String[] getPatientIds() {
        return new String[0];
    }

    @Override
    public Optional<VariantMasks> getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder) {
        throw new RuntimeException("Not Implemented");
    }
}
