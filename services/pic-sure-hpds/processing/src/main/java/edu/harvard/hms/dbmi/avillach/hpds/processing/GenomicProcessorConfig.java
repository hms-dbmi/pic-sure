package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.processing.genomic.GenomicProcessorRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class GenomicProcessorConfig {

    @Value("${HPDS_GENOMIC_DATA_DIRECTORY:/opt/local/hpds/all/}")
    private String hpdsGenomicDataDirectory;

    private static Logger log = LoggerFactory.getLogger(GenomicProcessorConfig.class);


    @Bean(name = "localGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "local")
    public GenomicProcessor localGenomicProcessor() {
        return new GenomicProcessorNodeImpl(hpdsGenomicDataDirectory);
    }

    @Bean(name = "localDistributedGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "localDistributed")
    public GenomicProcessor localDistributedGenomicProcessor() {
        List<GenomicProcessor> genomicProcessors = getGenomicProcessors(new File(hpdsGenomicDataDirectory));
        return new GenomicProcessorParentImpl(genomicProcessors);
    }

    @Bean(name = "localPatientDistributedGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "localPatientDistributed")
    public GenomicProcessor localPatientDistributedGenomicProcessor() {
        // assumed for now that all first level directories contain a genomic dataset for a group of studies
        File[] directories = new File(hpdsGenomicDataDirectory).listFiles(File::isDirectory);
        if (directories.length > 10) {
            throw new IllegalArgumentException(
                "Number of genomic partitions by studies exceeds maximum of 10 (" + directories.length + ")"
            );
        }

        List<GenomicProcessor> studyGroupedGenomicProcessors = new ArrayList<>();

        for (File directory : directories) {
            List<GenomicProcessor> genomicProcessors = getGenomicProcessors(directory);
            studyGroupedGenomicProcessors.add(new GenomicProcessorParentImpl(genomicProcessors));
        }

        return new GenomicProcessorPatientMergingParentImpl(studyGroupedGenomicProcessors);
    }

    private static List<GenomicProcessor> getGenomicProcessors(File directory) {
        File[] secondLevelDirectories = directory.listFiles(File::isDirectory);
        if (secondLevelDirectories.length > 50) {
            throw new IllegalArgumentException(
                "Number of chromosome partitions exceeds maximum of 50 (" + secondLevelDirectories.length + ")"
            );
        }

        return Flux.fromArray(secondLevelDirectories).flatMap(i -> Mono.fromCallable(() -> {
            return (GenomicProcessor) new GenomicProcessorNodeImpl(i.getAbsolutePath() + "/");
        }).subscribeOn(Schedulers.boundedElastic())).collectList().block();
    }

    @Bean(name = "localPatientOnlyDistributedGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "localPatientOnlyDistributed")
    public GenomicProcessor localPatientOnlyDistributedGenomicProcessor() {
        // assumed for now that all first level directories contain a genomic dataset for a group of studies
        File[] directories = new File(hpdsGenomicDataDirectory).listFiles(File::isDirectory);
        if (directories.length > 10) {
            throw new IllegalArgumentException(
                "Number of genomic partitions by studies exceeds maximum of 10 (" + directories.length + ")"
            );
        }

        List<GenomicProcessor> studyGroupedGenomicProcessors = new ArrayList<>();

        for (File directory : directories) {
            log.info("Loading partition: " + directory.getName());
            studyGroupedGenomicProcessors.add(new GenomicProcessorNodeImpl(directory.getAbsolutePath() + "/"));
        }

        return new GenomicProcessorPatientMergingParentImpl(studyGroupedGenomicProcessors);
    }

    @Bean(name = "remoteGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "remote")
    public GenomicProcessor remoteGenomicProcessor(@Value("${hpds.genomicProcessor.remoteHosts}") List<String> remoteHosts) {
        List<GenomicProcessor> genomicProcessors = Flux.fromIterable(remoteHosts)
            .flatMap(
                remoteHost -> Mono.fromCallable(() -> (GenomicProcessor) new GenomicProcessorRestClient(remoteHost))
                    .subscribeOn(Schedulers.boundedElastic())
            ).collectList().block();
        // todo: validate remote processors are valid
        return new GenomicProcessorParentImpl(genomicProcessors);
    }

    @Bean
    @ConditionalOnMissingBean
    public GenomicProcessor noOpGenomicProcessor() {
        return new GenomicProcessorNoOp();
    }
}
