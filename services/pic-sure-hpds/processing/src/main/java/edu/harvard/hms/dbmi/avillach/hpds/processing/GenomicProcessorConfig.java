package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.processing.genomic.GenomicProcessorRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@SpringBootApplication
@PropertySource("classpath:application.properties")
public class GenomicProcessorConfig {

    @Value("${HPDS_GENOMIC_DATA_DIRECTORY:/opt/local/hpds/all/}")
    private String hpdsGenomicDataDirectory;


    @Bean(name = "localGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "local")
    public GenomicProcessor localGenomicProcessor() {
        // todo: make sure this is set as default
        //System.getProperty("HPDS_GENOMIC_DATA_DIRECTORY", "/opt/local/hpds/all/");
        return new GenomicProcessorNodeImpl(hpdsGenomicDataDirectory);
    }

    @Bean(name = "localDistributedGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "localDistributed")
    public GenomicProcessor localDistributedGenomicProcessor() {
        //System.getProperty("HPDS_GENOMIC_DATA_DIRECTORY", "/opt/local/hpds/all/");
        List<GenomicProcessor> processorNodes = IntStream.range(1, 22)
                .mapToObj(i -> new GenomicProcessorNodeImpl(hpdsGenomicDataDirectory + "/" + i))
                .collect(Collectors.toList());
        return new GenomicProcessorParentImpl(processorNodes);
    }

    @Bean(name = "remoteGenomicProcessor")
    @ConditionalOnProperty(prefix = "hpds.genomicProcessor", name = "impl", havingValue = "remote")
    public GenomicProcessor remoteGenomicProcessor() {
        // Just for testing, for now, move to a configuration file or something
        List<GenomicProcessor> nodes = new ArrayList<>();
        String[] hosts = new String[] {"http://localhost:8090/", "http://localhost:8091/"};
        nodes = List.of(
                new GenomicProcessorRestClient(hosts[0]),
                new GenomicProcessorRestClient(hosts[1])
        );
        return new GenomicProcessorParentImpl(nodes);
    }
}
