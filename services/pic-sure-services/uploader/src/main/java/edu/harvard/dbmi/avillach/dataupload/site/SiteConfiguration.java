package edu.harvard.dbmi.avillach.dataupload.site;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Configuration
public class SiteConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SiteConfiguration.class);

    @Value("${aws.s3.institution:}")
    private List<String> allSites;

    @Value("${institution.name}")
    private String home;

    @Value("${institution.short-display}")
    private String display;

    @Autowired
    private ConfigurableApplicationContext context;

    @Bean
    public SiteListing getSiteInfo() {
        List<String> otherSites = allSites.stream().filter(Predicate.not(home::equals)).toList();
        if (otherSites.size() == allSites.size()) {
            LOG.error("Home site {} not present in listing of institutions: {}", home, allSites);
            context.close();
            return new SiteListing(List.of(), "", "");
        }

        // we want the home inst first. Makes frontend display a bit nicer
        List<String> sites = Stream.concat(Stream.of(home), otherSites.stream()).toList();

        return new SiteListing(sites, home, display);
    }
}
