package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.processing.genomic.GenomicProcessorRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
        genomicProcessors.add(new GenomicProcessorNodeImpl(hpdsGenomicDataDirectory + "/X/"));
        return new GenomicProcessorParentImpl(genomicProcessors);
    }

    @Bean(name = "localPatientDistributedGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "localPatientDistributed")
    public GenomicProcessor localPatientDistributedGenomicProcessor() {
        // assumed for now that all first level directories contain a genomic dataset for a group of studies
        File[] directories = new File(hpdsGenomicDataDirectory).listFiles(File::isDirectory);
        if (directories.length > 10) {
            throw new IllegalArgumentException("Number of genomic partitions by studies exceeds maximum of 10 (" + directories.length + ")");
        }

        List<GenomicProcessor> studyGroupedGenomicProcessors = new ArrayList<>();

        for (File directory : directories) {
            List<GenomicProcessor> genomicProcessors = Flux.range(1, 22)
                    .flatMap(i -> Mono.fromCallable(() -> (GenomicProcessor) new GenomicProcessorNodeImpl(directory.getAbsolutePath() + "/" + i + "/")).subscribeOn(Schedulers.boundedElastic()))
                    .collectList()
                    .block();
            genomicProcessors.add(new GenomicProcessorNodeImpl(directory + "/X/"));
            studyGroupedGenomicProcessors.add(new GenomicProcessorParentImpl(genomicProcessors));
        }

        return new GenomicProcessorPatientMergingParentImpl(studyGroupedGenomicProcessors);
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

    @Bean
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", matchIfMissing = true)
    public GenomicProcessor noOpGenomicProcessor() {
        return new GenomicProcessorNoOp();
    }
}
