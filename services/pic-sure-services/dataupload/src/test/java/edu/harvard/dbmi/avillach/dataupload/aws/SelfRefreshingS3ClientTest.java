package edu.harvard.dbmi.avillach.dataupload.aws;

import edu.harvard.dbmi.avillach.dataupload.status.ClientStatusService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.internal.verification.Times;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.encryption.s3.S3EncryptionClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SpringBootTest
class SelfRefreshingS3ClientTest {

    @Mock
    StsClient stsClient;

    @Mock
    ClientStatusService statusService;

    @Mock
    S3EncryptionClient.Builder encryptionClientBuilder;

    @Mock
    S3EncryptionClient encryptionClient;

    @InjectMocks
    SelfRefreshingS3Client subject;


    @Test
    void shouldDoHappyPath() {
        Credentials credentials = Credentials.builder()
            .accessKeyId(":)")
            .secretAccessKey(">:|")
            .sessionToken(":]")
            .expiration(Instant.MIN)
            .build();
        AssumeRoleResponse response = makeResponse(credentials);

        ReflectionTestUtils.setField(subject, "kmsKeyId", "key-id");
        ReflectionTestUtils.setField(subject, "locks", Map.of("BCH", new ReentrantReadWriteLock()));
        ReflectionTestUtils.setField(subject, "stsClients", Map.of("BCH", stsClient));
        Mockito.when(stsClient.assumeRole(Mockito.any(AssumeRoleRequest.class)))
            .thenReturn(response);

        Mockito.when(encryptionClientBuilder.wrappedClient(Mockito.any()))
            .thenReturn(encryptionClientBuilder);
        Mockito.when(encryptionClientBuilder.kmsKeyId(Mockito.any()))
            .thenReturn(encryptionClientBuilder);
        Mockito.when(encryptionClientBuilder.build())
            .thenReturn(encryptionClient);

        subject.refreshClient("BCH");
        S3Client actual = subject.getS3Client("BCH");

        Assertions.assertEquals(encryptionClient, actual);
        Mockito.verify(statusService, Mockito.times(1)).setClientStatus("initializing");
        Mockito.verify(statusService, Mockito.times(1)).setClientStatus("ready");
    }

    private AssumeRoleResponse makeResponse(Credentials credentials) {
        return AssumeRoleResponse.builder()
            .credentials(credentials)
            .build();
    }
}