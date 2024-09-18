package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.*;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class RASAuthenticationServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private AccessRuleService accessRuleService;
    @Mock
    private RestClientUtil restClientUtil;
    @Mock
    private ConnectionWebService connectionService;
    private RASPassPortService rasPassPortService;
    private RASAuthenticationService rasAuthenticationService;

    private final String testAccessToken = "someRandomAccessToken";
    private final String code = "123123123";
    private final String testDomain = "https://testdomain.com";
    private Map<String, String> authRequest;
    private final String exampleRasPassport = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsImtpZCI6ImRlZmF1bHRfc3NsX2tleSJ9.ew0KInN1YiI6IjJLRWthUC1SeDJGdkJCOExRVjRucjVmZXlySG4yNXEwV3hVd1kxVDIwMnMiLA0KImp0aSI6ImNiZDFjMzkyLTk0YjYtNDc2Yi1iYjA5LTk2MWY4MTM3MmE2NCIsDQoic2NvcGUiOiJvcGVuaWQgZ2E0Z2hfcGFzc3BvcnRfdjEiLA0KInR4biI6IlRsdVJ1UVcvZlZrPS5mYWJkOTdkMTdkNGM4OGFiIiwNCiJpc3MiOiAiaHR0cHM6Ly9zdHNzdGcubmloLmdvdiIsIAoiaWF0IjogMTYyMDIxMDM2MiwKImV4cCI6IDE2MjAyNTM1NjIsCiJnYTRnaF9wYXNzcG9ydF92MSIgOiBbImV3MEtJQ0FpZEhsd0lqb2dJa3BYVkNJc0RRb2dJQ0poYkdjaU9pQWlVbE15TlRZaUxBMEtJQ0FpYTJsa0lqb2dJbVJsWm1GMWJIUmZjM05zWDJ0bGVTSU5DbjAuZXcwS0lDQWlhWE56SWpvZ0ltaDBkSEJ6T2k4dmMzUnpjM1JuTG01cGFDNW5iM1lpTEEwS0lDQWljM1ZpSWpvZ0lqSkxSV3RoVUMxU2VESkdka0pDT0V4UlZqUnVjalZtWlhseVNHNHlOWEV3VjNoVmQxa3hWREl3TW5NaUxDQU5DaUFnSW1saGRDSTZJREUyTWpBeU1UQXpOaklzRFFvZ0lDSmxlSEFpT2lBeE5qSXdNalV6TlRZeUxBMEtJQ0FpYzJOdmNHVWlPaUFpYjNCbGJtbGtJR2RoTkdkb1gzQmhjM053YjNKMFgzWXhJaXdOQ2lBZ0ltcDBhU0k2SUNJNU56UTNPV0UzTXkwMFltSmxMVFJoWVdVdE9HWTFNUzAxTldVNU9UQTBZalJqT1RnaUxBMEtJQ0FpZEhodUlqb2dJbFJzZFZKMVVWY3ZabFpyUFM1bVlXSmtPVGRrTVRka05HTTRPR0ZpSWl3TkNpQWdJbWRoTkdkb1gzWnBjMkZmZGpFaU9pQjdJQTBLSUNBZ0lDQWlkSGx3WlNJNklDSm9kSFJ3Y3pvdkwzSmhjeTV1YVdndVoyOTJMM1pwYzJGekwzWXhMakVpTENBTkNpQWdJQ0FnSW1GemMyVnlkR1ZrSWpvZ01UWXlNREl4TURNMk1pd05DaUFnSUNBZ0luWmhiSFZsSWpvZ0ltaDBkSEJ6T2k4dmMzUnpjM1JuTG01cGFDNW5iM1l2Y0dGemMzQnZjblF2WkdKbllYQXZkakV1TVNJc0RRb2dJQ0FnSUNKemIzVnlZMlVpT2lBaWFIUjBjSE02THk5dVkySnBMbTVzYlM1dWFXZ3VaMjkyTDJkaGNDSXNEUW9nSUNBZ0lDSmllU0k2SUNKa1lXTWlmU3dOQ2lBZ0lDQWdJbkpoYzE5a1ltZGhjRjl3WlhKdGFYTnphVzl1Y3lJNklGc05DaUFnSUNBZ0lDQWdJQTBLZXcwS0ltTnZibk5sYm5SZmJtRnRaU0k2SWtkbGJtVnlZV3dnVW1WelpXRnlZMmdnVlhObElpd0pEUW9pY0doelgybGtJam9pY0doek1EQXdNREEySWl3TkNpSjJaWEp6YVc5dUlqb2lkakVpTEEwS0luQmhjblJwWTJsd1lXNTBYM05sZENJNkluQXhJaXdKQ1EwS0ltTnZibk5sYm5SZlozSnZkWEFpT2lKak1TSXNEUW9pY205c1pTSTZJbkJwSWl3TkNpSmxlSEJwY21GMGFXOXVJam94TmpReE1ERXpNakF3RFFwOUxBMEtldzBLSW1OdmJuTmxiblJmYm1GdFpTSTZJa1Y0WTJoaGJtZGxJRUZ5WldFaUxBa05DaUp3YUhOZmFXUWlPaUp3YUhNd01EQXpNREFpTEEwS0luWmxjbk5wYjI0aU9pSjJNU0lzRFFvaWNHRnlkR2xqYVhCaGJuUmZjMlYwSWpvaWNERWlMQWtKRFFvaVkyOXVjMlZ1ZEY5bmNtOTFjQ0k2SW1NNU9Ua2lMQTBLSW5KdmJHVWlPaUp3YVNJc0RRb2laWGh3YVhKaGRHbHZiaUk2TVRZME1UQXhNekl3TUFrTkNuME5DaUFnSUNBZ1hTQU5DbjAuTnpSOEtzZTJOOUtFOXhvLUo4dXdUaWxzUG9pYXhNWGlGR0prY0JOYTMtOGt1ZEh3MFd6U0xDM3Z3Qk4yZ3Z0RUtMZ2ZBeVpVUDZrc0ktRzlOV0NIU3Z2RG4tbFNhbjVtV1dfWEhrRVdGWGd3RXotWlNNalBvV0Vndlk1bHhSWEhxR1lhWmQ5U2puTjdsTFpUbHNQLU9pbFUxcUNyQ205YzVfcTh1YWJyZ3o0OW5PWFRGZEpKblpPT1ZzUmtkU0NjVnlHczRlbUxNSjdDdVd2ckU2RkR2Ri1QTUpGNlhHYnN3R1pjVFRPM3h0MjR6Tk1wbm5RUEVzNXQ3Tk1LZjhucEJ3czNvd0FKcklTRkExYTNmUWtJZU83dFRUUGVSX1FRVUlxYzFJRW5JdlotMGdsNE5ETEZRSjJTTS1KdUtvSWdnQWt3NVNGWDNhSk9WNC12b2JZbXhBIl0NCn0.sJwAZeR8cYyF-BCluC9fmiQAi14L7hC3DB4MoFQNNdoakUBujPZ-NlpfP2rBgJQ3CGcxsF95Vdczm6Yk4TKa68eXkKjkswjsSSQg0qErgFhN2jis9KMxnMfmfPNUfb0lioHtD-_oghRkd9239oUwLR06KB5Ux3mD4Pc0ZPbJxJcPmyP9DZ8WEHmAFIJpcoayHwJDr1jt-GbqUtaTCs1VQ9Habh8Z8fvwrlvQNj744m5eq6141bD0G15KgvbyYf9L4_PYNgMjTyUx9EGyetrxQ4XmOpDF_ZbFEhZliy80qfO2HGQzSId-dKXCvPI_SUWcCVeJqPwmXTirTt9qJ63ypw";

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        RoleService roleService = new RoleService(mock(RoleRepository.class), mock(PrivilegeService.class), mock(FenceMappingUtility.class));
        this.rasPassPortService = spy(new RASPassPortService(restClientUtil, userService, ""));
        doReturn(false).when(rasPassPortService).isExpired(any());

        rasAuthenticationService = new RASAuthenticationService(
                userService,
                accessRuleService,
                restClientUtil,
                true,
                "test.com",
                "",
                "",
                "",
                "https://stsstg.nih.gov",
                roleService,
                rasPassPortService,
                connectionService
        );

        Connection rasConnection = new Connection();
        rasConnection.setSubPrefix("okta-ras|");
        rasConnection.setUuid(UUID.randomUUID());
        rasConnection.setId("okta-ras");
        rasConnection.setLabel("RAS");
        rasAuthenticationService.setRasConnection(rasConnection);

        authRequest = new HashMap<>();
        authRequest.put("code", code);
        authRequest.put("redirectURI", testDomain);
    }

    @Test
    public void testAuthorizationCodeFlow_Successful() {
        String data = "{\"access_token\":\"" + testAccessToken + "\", \"active\":true, \"id_token\":\"SomeRandomToken\"}";
        String payload = "token_type_hint=access_token&token=" + testAccessToken;
        String redirectUri = "https://" + testDomain + "/login/loading";
        String queryString = "grant_type=authorization_code" + "&code=" + code + "&redirect_uri=" + redirectUri;
        String introspectionResponse =
                "{\"active\":true,\"sub\":\"example_email@test.com\",\"client_id\":\"test_client_id\",\"passport_jwt_v11\":\""+ exampleRasPassport +"\"}";

        // token exchange
        when(restClientUtil.retrievePostResponse(anyString(), any(), eq(queryString))).thenReturn(ResponseEntity.ok(data));

        // introspect
        when(restClientUtil.retrievePostResponse(anyString(), any(), eq(payload))).thenReturn(ResponseEntity.ok(introspectionResponse));

        User user = createTestUser();
        user.setSubject("okta-ras|adfadfaf");
        when(userService.createRasUser(any(), any())).thenReturn(Optional.of(user));
        when(userService.updateUserRoles(any(), any())).thenReturn(user);
        HashMap<String, String> authenticate = rasAuthenticationService.authenticate(authRequest, testDomain);
        assertNotNull(authenticate);
    }

    private User createTestUser() {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(new HashSet<>(Collections.singleton(createTestRole())));
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@email.com");
        user.setAcceptedTOS(new Date());
        user.setActive(true);

        return user;
    }

    private Role createTestRole() {
        Role role = new Role();
        role.setName("TEST_ROLE");
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Collections.singleton(createTestPrivilege()));
        return role;
    }

    private Privilege createTestPrivilege() {
        Privilege privilege = new Privilege();
        privilege.setName("TEST_PRIVILEGE");
        privilege.setUuid(UUID.randomUUID());
        privilege.setQueryTemplate(createQueryTemplate("consent_concept_path_"+privilege.getUuid(), "project_name_"+privilege.getUuid(), "consent_group_"+privilege.getUuid()));

        return privilege;
    }

    private String createQueryTemplate(String consent_concept_path, String project_name, String consent_group) {
        return "{\"categoryFilters\": {\""
                + consent_concept_path
                + "\":\""
                + project_name + "." + consent_group
                + "\"},"
                + "\"numericFilters\":{},\"requiredFields\":[],"
                + "\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],"
                + "\"expectedResultType\": \"COUNT\""
                + "}";
    }
}
