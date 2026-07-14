package edu.harvard.dbmi.avillach.dataupload.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;

import java.util.Optional;

@Service
public class StsClientProvider {

    private static final Logger log = LoggerFactory.getLogger(StsClientProvider.class);

    public Optional<StsClient> createClient() {
        StsClient client = StsClient.builder().region(Region.US_EAST_1).build();
        return Optional.of(client);
    }
}
