package edu.harvard.dbmi.avillach.dataupload.upload.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dataupload.aws.AWSCredentialsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.core.SdkBytes;

import java.util.Optional;
import java.util.UUID;

@Component
public class POSTUrlFetcher {
    private static final Logger log = LoggerFactory.getLogger(POSTUrlFetcher.class);

    private final AWSCredentialsService credentialsService;
    private final Region region;
    private final String labdaARN;
    private final String bucketName;

    @Autowired
    public POSTUrlFetcher(
        AWSCredentialsService credentialsService,
        @Value("${aws.region}") String region,
        @Value("${cumulus.lambda:}") String labdaARN,
        @Value("${cumulus.bucket:}") String bucketName
    ) {
        this.credentialsService = credentialsService;
        this.region = Region.of(region);
        this.labdaARN = labdaARN;
        this.bucketName = bucketName;
    }

    public Optional<String> getPreSignedUploadURL(String uploadUUID, String fileName) {
        AwsCredentials awsCredentials = credentialsService.constructCredentials();
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(awsCredentials);


        try (
            LambdaClient lambdaClient = LambdaClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build()
        ) {

            String payload = new Payload(uploadUUID, fileName, bucketName).asJson();
            log.info("Created upload request payload of {}", payload);

            InvokeRequest invokeRequest = InvokeRequest.builder()
                .functionName(labdaARN)
                .payload(SdkBytes.fromUtf8String(payload))
                .build();

            log.info("Invoking lambda");
            InvokeResponse invokeResponse = lambdaClient.invoke(invokeRequest);
            log.info("Returning lambda response");
            return parseResponse(invokeResponse.payload().asUtf8String());
        }
    }

    private Optional<String> parseResponse(String raw) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            JsonNode jsonNode = objectMapper.readTree(raw);
            return Optional.ofNullable(jsonNode.get("presigned_url").asText());
        } catch (JsonProcessingException e) {
            log.error("Error parsing json: ", e);
            return Optional.empty();
        }
    }

    private record Payload(String object_key, String bucket_name) {
        Payload(String directory, String fileName, String bucketName) {
            this(directory + "/" + fileName, bucketName);
        }

        public String asJson() {
            try {
                return new ObjectMapper().writeValueAsString(this);
            } catch (JsonProcessingException e) {
                log.error("Could not make payload: ", e);
                return "";
            }
        }
    }
}