package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.processing.genomic.GenomicProcessorRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@SpringBootApplication
public class GenomicProcessorConfig {

    @Value("${HPDS_GENOMIC_DATA_DIRECTORY:/opt/local/hpds/all/}")
    private String hpdsGenomicDataDirectory;


    @Bean(name = "localGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "local")
    public GenomicProcessor localGenomicProcessor() {
        return new GenomicProcessorNodeImpl(hpdsGenomicDataDirectory);
    }

    @Bean(name = "localDistributedGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "localDistributed")
    public GenomicProcessor localDistributedGenomicProcessor() {
        List<GenomicProcessor> genomicProcessors = Flux.range(1, 22)
                .flatMap(i -> Mono.fromCallable(() -> (GenomicProcessor) new GenomicProcessorNodeImpl(hpdsGenomicDataDirectory + "/" + i + "/")).subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .block();
        return new GenomicProcessorParentImpl(genomicProcessors);
    }

    @Bean(name = "remoteGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "remote")
    public GenomicProcessor remoteGenomicProcessor(@Value("${hpds.genomicProcessor.remoteHosts}") List<String> remoteHosts) {
        List<GenomicProcessor> genomicProcessors = Flux.fromIterable(remoteHosts)
                .flatMap(remoteHost -> Mono.fromCallable(() -> (GenomicProcessor) new GenomicProcessorRestClient(remoteHost)).subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .block();
        // todo: validate remote processors are valid
        return new GenomicProcessorParentImpl(genomicProcessors);
    }
}
