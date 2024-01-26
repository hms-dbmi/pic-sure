package edu.harvard.dbmi.avillach.dataupload.aws;

import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.encryption.s3.S3EncryptionClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@ConditionalOnProperty(name = "production", havingValue = "true")
@Configuration
public class AWSConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(AWSConfiguration.class);

    @Value("${aws.s3.access_key_secret:}")
    private String secret;

    @Value("${aws.s3.access_key_id:}")
    private String key;

    @Value("${aws.s3.session_token:}")
    private String token;

    @Value("${aws.s3.institution:}")
    private List<String> institutions;

    @Value("${aws.s3.role_arns:}")
    private List<String> roleArns;

    @Value("${aws.s3.external_ids:}")
    private List<String> externalIDs;

    @Value("${aws.s3.buckets:}")
    private List<String> buckets;

    @Value("${aws.kms.key_ids:}")
    private List<String> kmsKeyIds;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired(required = false)
    private SdkHttpClient sdkHttpClient;

    @Value("${http.proxyUser:}")
    private String proxyUser;

    @Bean
    @ConditionalOnProperty(name = "production", havingValue = "true")
    public StsClient stsClients(
        @Autowired AwsCredentials credentials,
        @Autowired StsClientBuilder stsClientBuilder
    ) {
        StsClientBuilder builder = stsClientBuilder
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(credentials));

        if (StringUtils.hasLength(proxyUser)) {
            builder.httpClient(sdkHttpClient);
        }

        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "aws.authentication.method", havingValue = "user")
    AwsCredentials credentials() {
        LOG.info("Authentication method is user. Attempting to resolve user credentials.");
        if (Strings.isBlank(key)) {
            LOG.error("No AWS key. Can't create client. Exiting");
            context.close();
        }
        if (Strings.isBlank(secret)) {
            LOG.error("No AWS secret. Can't create client. Exiting");
            context.close();
        }
        if (Strings.isBlank(token)) {
            return AwsBasicCredentials.create(key, secret);
        } else {
            return AwsSessionCredentials.create(key, secret, token);
        }
    }
    @Bean
    @ConditionalOnProperty(name = "aws.authentication.method", havingValue = "instance-profile")
    AwsCredentials ipCredentials() {
        LOG.info("Authentication method is instance-profile. Attempting to resolve instance profile credentials.");
        return InstanceProfileCredentialsProvider.create().resolveCredentials();
    }

    @Bean
    @ConditionalOnProperty(name = "production", havingValue = "true")
    Map<String, SiteAWSInfo> roleARNs() {
        boolean badConfig = Stream.of(roleArns, externalIDs, kmsKeyIds, buckets)
            .filter(l -> l.size() != institutions.size())
            .peek(l -> LOG.error("Mismatched aws credentials {}", l))
            .findAny()
            .isPresent();
        if (badConfig) {
            context.close();
            return Map.of();
        }
        HashMap<String, SiteAWSInfo> roles = new HashMap<>();
        for (int i = 0; i < institutions.size(); i++) {
            SiteAWSInfo info = new SiteAWSInfo(
                institutions.get(i), roleArns.get(i), externalIDs.get(i), buckets.get(i), kmsKeyIds.get(i)
            );
            roles.put(info.siteName(), info);
        }
        return roles;
    }

    @Bean
    S3EncryptionClient.Builder encryptionClientBuilder() {
        // This is a bean for mocking purposes
        return S3EncryptionClient.builder();
    }

    @Bean
    StsClientBuilder stsClientBuilder() {
        // This is a bean for mocking purposes
        return StsClient.builder();
    }
}
