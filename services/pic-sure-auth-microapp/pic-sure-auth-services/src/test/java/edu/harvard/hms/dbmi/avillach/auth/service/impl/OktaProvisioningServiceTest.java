package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.config.OktaProvisioningConfig;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.IdpProvisioningException;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OktaProvisioningServiceTest {

    private final RestClientUtil restClientUtil = mock(RestClientUtil.class);
    private final OktaProvisioningConfig oktaProvisioningConfig = mock(OktaProvisioningConfig.class);
    private final OktaProvisioningService service = new OktaProvisioningService(restClientUtil, oktaProvisioningConfig);

    @BeforeEach
    public void setUp() {
        when(oktaProvisioningConfig.getInvokeUrl()).thenReturn("https://example.okta.com/api/flo/xyz/invoke");
        when(oktaProvisioningConfig.getClientToken()).thenReturn("s3cr3t");
        when(oktaProvisioningConfig.getApplicationAccess()).thenReturn("PICSURE");
        when(oktaProvisioningConfig.getDivision()).thenReturn("PICSURE");
        when(oktaProvisioningConfig.getDefaultUserType()).thenReturn("PIC-SURE User");

        when(restClientUtil.retrievePostResponseWithRequestConfiguration(anyString(), any(HttpHeaders.class), anyString(), anyInt()))
            .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));
    }

    private User createTestUser() {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail("new.user@example.com");
        user.setGeneralMetadata("{\"firstName\":\"New\",\"lastName\":\"User\"}");
        Connection connection = new Connection().setId("TEST_CONNECTION").setLabel("Test IdP");
        user.setConnection(connection);
        return user;
    }

    @Test
    public void provisionUser_sendsExpectedPayloadAndClientTokenInUrl() {
        User user = createTestUser();

        service.provisionUser(user);

        verify(restClientUtil).retrievePostResponseWithRequestConfiguration(
            eq("https://example.okta.com/api/flo/xyz/invoke?clientToken=s3cr3t"), any(HttpHeaders.class), argThat(
                body -> body.contains("\"email\":\"new.user@example.com\"") && body.contains("\"firstName\":\"New\"")
                    && body.contains("\"lastName\":\"User\"") && body.contains("\"isActive\":\"active\"")
                    && body.contains("\"ApplicationAccess\":\"PICSURE\"") && body.contains("\"identityProviderName\":\"Test IdP\"")
                    && body.contains("\"requestID\":\"" + user.getUuid() + "\"")
                    && body.contains("\"username\":\"new.user-" + user.getUuid().toString().substring(0, 4) + "\"")
            ), anyInt()
        );
    }

    @Test
    public void deprovisionUser_sendsInactiveStatus() {
        User user = createTestUser();

        service.deprovisionUser(user);

        verify(restClientUtil).retrievePostResponseWithRequestConfiguration(
            anyString(), any(HttpHeaders.class), argThat(body -> body.contains("\"isActive\":\"inactive\"")), anyInt()
        );
    }

    @Test
    public void provisionUser_missingNameMetadataDefaultsToBlank() {
        User user = createTestUser();
        user.setGeneralMetadata(null);

        service.provisionUser(user);

        verify(restClientUtil).retrievePostResponseWithRequestConfiguration(
            anyString(), any(HttpHeaders.class),
            argThat(body -> body.contains("\"firstName\":\"\"") && body.contains("\"lastName\":\"\"")), anyInt()
        );
    }

    @Test
    public void provisionUser_wrapsHttpFailureInIdpProvisioningException() {
        User user = createTestUser();
        when(restClientUtil.retrievePostResponseWithRequestConfiguration(anyString(), any(HttpHeaders.class), anyString(), anyInt()))
            .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(), new byte[0], null));

        assertThrows(IdpProvisioningException.class, () -> service.provisionUser(user));
    }

    @Test
    public void provisionUser_exceptionMessageNeverContainsClientToken() {
        User user = createTestUser();
        when(restClientUtil.retrievePostResponseWithRequestConfiguration(anyString(), any(HttpHeaders.class), anyString(), anyInt()))
            .thenThrow(new RuntimeException("connection refused"));

        IdpProvisioningException ex = assertThrows(IdpProvisioningException.class, () -> service.provisionUser(user));
        assertFalse(ex.getMessage().contains("s3cr3t"));
    }
}
