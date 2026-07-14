package edu.harvard.dbmi.avillach.dictionary.memory;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MemoryCheck {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryCheck.class);

    @PostConstruct
    public void checkMemory() {
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        LOG.info("Max Heap Memory (Xmx): {} MB", maxMemory);
    }

}
