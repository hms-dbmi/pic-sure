package edu.harvard.dbmi.avillach.dataupload.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.util.Map;
import java.util.Optional;

@Profile("!dev")
@Service
public class AWSClientBuilder {

    private static final Logger log = LoggerFactory.getLogger(AWSClientBuilder.class);

    private final Map<String, SiteAWSInfo> sites;
    private final StsClientProvider stsClientProvider;
    private final S3ClientBuilder s3ClientBuilder;
    private final SdkHttpClient sdkHttpClient;
    private final boolean retainRole;

    @Autowired
    public AWSClientBuilder(
        Map<String, SiteAWSInfo> sites,
        StsClientProvider stsClientProvider,
        S3ClientBuilder s3ClientBuilder,
        @Autowired(required = false) SdkHttpClient sdkHttpClient,
        @Value("${s3.retain_role:false}") boolean retainRole
    ) {
        this.sites = sites;
        this.stsClientProvider = stsClientProvider;
        this.s3ClientBuilder = s3ClientBuilder;
        this.sdkHttpClient = sdkHttpClient;
        this.retainRole = retainRole;
    }

    public Optional<S3Client> buildClientForSite(String siteName) {
        log.info("Building client for site {}", siteName);
        if (!sites.containsKey(siteName)) {
            log.warn("Could not find site {}", siteName);
            return Optional.empty();
        }

        if (retainRole) {
            log.info("s3.retain_role set to true. Will retain current role rather than assuming one for site");
            InstanceProfileCredentialsProvider credentialsProvider = InstanceProfileCredentialsProvider.create();
            S3Client client = s3ClientBuilder
                .credentialsProvider(credentialsProvider)
                .build();
            return Optional.of(client);
        }

        log.info("Found site, making assume role request");
        SiteAWSInfo site = sites.get(siteName);
        AssumeRoleRequest roleRequest = AssumeRoleRequest.builder()
            .roleArn(site.roleARN())
            .roleSessionName("test_session" + System.nanoTime())
            .externalId(site.externalId())
            .durationSeconds(60*60) // 1 hour
            .build();
        Optional<Credentials> assumeRoleResponse = stsClientProvider.createClient()
            .map(c -> c.assumeRole(roleRequest))
            .map(AssumeRoleResponse::credentials);
        if (assumeRoleResponse.isEmpty() ) {
            log.error("Error assuming role {} , no credentials returned", site.roleARN());
            return Optional.empty();
        }
        log.info("Successfully assumed role {} for site {}", site.roleARN(), site.siteName());

        log.info("Building S3 client for site {}", site.siteName());
        // Use the credentials from the role to create the S3 client
        Credentials credentials = assumeRoleResponse.get();
        AwsSessionCredentials sessionCredentials = AwsSessionCredentials.builder()
            .accessKeyId(credentials.accessKeyId())
            .secretAccessKey(credentials.secretAccessKey())
            .sessionToken(credentials.sessionToken())
            .expirationTime(credentials.expiration())
            .build();
        StaticCredentialsProvider provider = StaticCredentialsProvider.create(sessionCredentials);
        return Optional.of(buildFromProvider(provider));
    }

    private S3Client buildFromProvider(StaticCredentialsProvider provider) {
        if (sdkHttpClient == null) {
            return s3ClientBuilder.credentialsProvider(provider).build();
        }
        log.info("Http proxy detected and added to S3 client");
        return s3ClientBuilder
            .credentialsProvider(provider)
            .httpClient(sdkHttpClient)
            .build();

    }

}
