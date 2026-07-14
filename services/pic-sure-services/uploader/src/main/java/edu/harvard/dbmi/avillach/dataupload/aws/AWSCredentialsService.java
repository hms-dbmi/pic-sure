package edu.harvard.dbmi.avillach.dataupload.aws;

import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;

@Service
public class AWSCredentialsService {

    private static final Logger LOG = LoggerFactory.getLogger(AWSCredentialsService.class);

    private final String authMethod;
    private final String secret;
    private final String key;
    private final String token;
    private final ConfigurableApplicationContext context;


    private AwsCredentials credentials;

    @Autowired
    public AWSCredentialsService(
        @Value("${aws.authentication.method:}") String authMethod,
        @Value("${aws.s3.access_key_secret:}") String secret,
        @Value("${aws.s3.access_key_id:}") String key,
        @Value("${aws.s3.session_token:}") String token,
        ConfigurableApplicationContext context
    ) {
        this.authMethod = authMethod;
        this.secret = secret;
        this.key = key;
        this.token = token;
        this.context = context;
    }

    public AwsCredentials constructCredentials() {
        //noinspection SwitchStatementWithTooFewBranches
        return switch (authMethod) {
            case "instance-profile" -> createInstanceProfileBasedCredentials();
            default -> createUserBasedCredentials();
        };
    }

    private AwsCredentials createUserBasedCredentials() {
        LOG.info("Authentication method is user. Attempting to resolve user credentials.");
        if (Strings.isBlank(key)) {
            LOG.error("No AWS key. Can't create client. Exiting");
            context.close();
            return null;
        }
        if (Strings.isBlank(secret)) {
            LOG.error("No AWS secret. Can't create client. Exiting");
            context.close();
            return null;
        }
        if (Strings.isBlank(token)) {
            return AwsBasicCredentials.create(key, secret);
        } else {
            return AwsSessionCredentials.create(key, secret, token);
        }
    }

    private AwsCredentials createInstanceProfileBasedCredentials() {
        LOG.info("Authentication method is instance-profile. Attempting to resolve instance profile credentials.");
        return InstanceProfileCredentialsProvider.create().resolveCredentials();
    }
}
