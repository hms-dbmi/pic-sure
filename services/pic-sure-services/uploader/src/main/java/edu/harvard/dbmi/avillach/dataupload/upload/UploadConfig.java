package edu.harvard.dbmi.avillach.dataupload.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

@Configuration
public class UploadConfig {
    @Bean
    public Semaphore getUploadLock(@Value("${max_concurrent_uploads:1}") Integer maxConcurrent) {
        return new Semaphore(maxConcurrent);
    }
}
