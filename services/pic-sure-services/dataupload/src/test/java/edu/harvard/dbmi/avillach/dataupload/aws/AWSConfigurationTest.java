package edu.harvard.dbmi.avillach.dataupload.aws;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AWSConfigurationTest {
    @Mock
    ConfigurableApplicationContext context;

    @Mock
    StsClientBuilder stsClientBuilder;

    @Mock
    StsClient stsClient;

    @InjectMocks
    AWSConfiguration subject;

    @Test
    void shouldCreateCredentials() {
        ReflectionTestUtils.setField(subject, "secrets", List.of("s1", "s2"));
        ReflectionTestUtils.setField(subject, "keyIds", List.of("k1", "k2"));
        ReflectionTestUtils.setField(subject, "tokens", List.of("t1", "t2"));
        ReflectionTestUtils.setField(subject, "institutions", List.of("i1", "i2"));

        Map<String, AwsCredentials> actual = subject.credentials();
        Map<String, AwsCredentials> expected = Map.of(
            "i1", AwsSessionCredentials.create("k1", "s1", "t1"),
            "i2", AwsSessionCredentials.create("k2", "s2", "t2")
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldNotCreateCredentials() {
        ReflectionTestUtils.setField(subject, "secrets", List.of("s1", "s2"));
        ReflectionTestUtils.setField(subject, "keyIds", List.of("k2"));
        ReflectionTestUtils.setField(subject, "tokens", List.of("t1", "t2"));
        ReflectionTestUtils.setField(subject, "institutions", List.of("i1", "i2"));

        subject.credentials();

        Mockito.verify(context, Mockito.times(1)).close();
    }

    @Test
    void shouldCreateClients() {
        Map<String, AwsCredentials> credentials = Map.of(
            "i1", AwsSessionCredentials.create("k1", "s1", "t1"),
            "i2", AwsSessionCredentials.create("k2", "s2", "t2")
        );
        Mockito.when(stsClientBuilder.region(Region.US_EAST_1))
            .thenReturn(stsClientBuilder);
        Mockito.when(stsClientBuilder.credentialsProvider(Mockito.any()))
            .thenReturn(stsClientBuilder);
        Mockito.when(stsClientBuilder.build())
            .thenReturn(stsClient);

        Map<String, StsClient> actual = subject.stsClients(credentials, stsClientBuilder);
        Map<String, StsClient> expected = Map.of("i1", stsClient, "i2", stsClient);

        Assertions.assertEquals(expected, actual);
    }
}