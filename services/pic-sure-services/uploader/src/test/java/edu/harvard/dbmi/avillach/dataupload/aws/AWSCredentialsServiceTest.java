package edu.harvard.dbmi.avillach.dataupload.aws;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AWSCredentialsServiceTest {

    @Mock
    ConfigurableApplicationContext context;

    @InjectMocks
    AWSCredentialsService subject;

    @Test
    void shouldCreateCredentials() {
        ReflectionTestUtils.setField(subject, "authMethod", "user");
        ReflectionTestUtils.setField(subject, "secret", "s1");
        ReflectionTestUtils.setField(subject, "key", "k1");
        ReflectionTestUtils.setField(subject, "token", "t1");

        AwsCredentials actual = subject.constructCredentials();
        AwsSessionCredentials expected = AwsSessionCredentials.create("k1", "s1", "t1");

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldNotCreateCredentials() {
        ReflectionTestUtils.setField(subject, "authMethod", "user");
        ReflectionTestUtils.setField(subject, "secret", "");
        ReflectionTestUtils.setField(subject, "key", "k1");
        ReflectionTestUtils.setField(subject, "token", "t1");

        subject.constructCredentials();

        Mockito.verify(context, Mockito.times(1)).close();
    }
}