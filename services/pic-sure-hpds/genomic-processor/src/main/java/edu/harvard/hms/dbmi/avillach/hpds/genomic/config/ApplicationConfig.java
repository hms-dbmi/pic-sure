package edu.harvard.hms.dbmi.avillach.hpds.genomic.config;

import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessorNodeImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

@SpringBootApplication
@PropertySource("classpath:application.properties")
public class ApplicationConfig {

    @Value( "${hpds.genomicDataDirectory}" )
    private String genomicDataDirectory;

    @Bean
    public GenomicProcessor genomicProcessor() {
        return new GenomicProcessorNodeImpl(genomicDataDirectory);
    }
}
