package edu.harvard.dbmi.avillach.dump.remote;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@Configuration
@EnableScheduling
@ConfigurationProperties(prefix = "remote")
public class RemoteDictionaryPullConfig {

    private List<RemoteDictionary> dictionaries;

    @Bean
    public List<RemoteDictionary> getDictionaries() {
        return dictionaries;
    }

    public void setDictionaries(List<RemoteDictionary> dictionaries) {
        this.dictionaries = dictionaries;
    }
}
