package edu.harvard.hms.dbmi.avillach.query.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.UserConsent;
import edu.harvard.hms.dbmi.avillach.query.operations.StoredQuery;

class ConsentAuthorizationServiceTest {

    @Test
    void callerSuppliedConsentsAreReplacedWithTheConsentsPsamaReports() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Set.of("phs001.c1"));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(query(Set.of(new UserConsent("phs999.c1")), ResultType.COUNT));

        service.scopeQuery("auth", request, "Bearer caller-token");

        Query scoped = (Query) request.getQuery();
        assertThat(scoped.userConsents()).containsExactly(new UserConsent("phs001.c1"));
    }

    @Test
    void disabledConsentAuthorizationLeavesQueryUntouched() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, false);
        Query query = query(Set.of(), ResultType.COUNT);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(query);

        service.scopeQuery("auth", request, "Bearer caller-token");

        assertThat(request.getQuery()).isSameAs(query);
        verifyNoInteractions(client);
    }

    @Test
    void disabledConsentAuthorizationLeavesSavedQueryReadsUntouched() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, false);

        service.verifyReadAccess("auth", storedWithConsents("phs001.c1"), null);

        verifyNoInteractions(client);
    }

    @Test
    void openBackendLeavesQueryUntouched() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);
        Query query = query(Set.of(), ResultType.COUNT);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(query);

        service.scopeQuery("open", request, "Bearer caller-token");

        assertThat(request.getQuery()).isSameAs(query);
        verifyNoInteractions(client);
    }

    @Test
    void queryMapFromHttpBindingIsConvertedToV3QueryBeforeScoping() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Set.of("phs001.c1"));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);
        GeneralQueryRequest request =
            new GeneralQueryRequest().setQuery(Map.of("select", List.of("\\Demographics\\Age\\"), "expectedResultType", "COUNT"));

        service.scopeQuery("auth", request, "Bearer caller-token");

        assertThat(request.getQuery()).isInstanceOf(Query.class);
        assertThat(((Query) request.getQuery()).userConsents()).containsExactly(new UserConsent("phs001.c1"));
    }

    /**
     * Scoping is keyed on the {@code auth} path segment, never on the body naming a result type. A query missing one is still consent
     * scoped rather than forwarded unscoped, which is what lets the v3 model accept it.
     */
    @Test
    void authQueryWithoutExpectedResultTypeIsStillScoped() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Set.of("phs001.c1"));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(Map.of("select", List.of("\\phs000999\\variable\\")));

        service.scopeQuery("auth", request, "Bearer caller-token");

        Query scoped = (Query) request.getQuery();
        assertThat(scoped.expectedResultType()).isNull();
        assertThat(scoped.userConsents()).containsExactly(new UserConsent("phs001.c1"));
    }

    @Test
    void authQueryWithoutCallerTokenFailsClosed() {
        ConsentAuthorizationService service = new ConsentAuthorizationService(mock(PsamaConsentClient.class), true);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(query(Set.of(), ResultType.COUNT));

        assertThatThrownBy(() -> service.scopeQuery("auth", request, null)).isInstanceOfSatisfying(PicsureException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.getErrorType()).isEqualTo("consent_lookup_failed");
        });
    }

    @Test
    void callerWithNoUsableConsentIsDenied() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Set.of("  "));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(query(Set.of(), ResultType.COUNT));

        assertThatThrownBy(() -> service.scopeQuery("auth", request, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getErrorType()).isEqualTo("consent_denied");
            });
    }

    @Test
    void savedConsentsThatRemainASubsetAreAllowed() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Set.of("phs001.c1", "phs002.c1"));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);

        service.verifyReadAccess("auth", storedWithConsents("phs001.c1"), "Bearer caller-token");
    }

    @Test
    void lostSavedConsentIsDenied() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Set.of("phs001.c1"));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);

        assertThatThrownBy(() -> service.verifyReadAccess("auth", storedWithConsents("phs002.c1"), "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getErrorType()).isEqualTo("consent_denied");
            });
    }

    @Test
    void everySavedConsentMustStillBeHeld() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Set.of("phs001.c1"));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);

        assertThatThrownBy(() -> service.verifyReadAccess("auth", storedWithConsents("phs001.c1", "phs002.c1"), "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void storedQueryWithoutUserConsentsIsDenied() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Set.of("phs001.c1"));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);
        StoredQuery stored = new StoredQuery(null, "{\"query\":{\"expectedResultType\":\"COUNT\"}}", "rr", null, "3", null);

        assertThatThrownBy(() -> service.verifyReadAccess("auth", stored, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /**
     * A query stored under the retired {@code authorizationFilters} model carries no {@code userConsents}, so it is no longer readable.
     */
    @Test
    void storedQueryCarryingOnlyLegacyAuthorizationFiltersIsDenied() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Set.of("phs001.c1"));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, true);
        StoredQuery stored = new StoredQuery(
            null, "{\"query\":{\"authorizationFilters\":[{\"conceptPath\":\"\\\\_consents\\\\\",\"values\":[\"phs001.c1\"]}]}}", "rr", null,
            "3", null
        );

        assertThatThrownBy(() -> service.verifyReadAccess("auth", stored, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static Query query(Set<UserConsent> userConsents, ResultType resultType) {
        return new Query(List.of(), List.of(), userConsents, null, List.of(), resultType, null, null);
    }

    private static StoredQuery storedWithConsents(String... consents) {
        String values = String.join(",", java.util.Arrays.stream(consents).map(c -> "{\"value\":\"" + c + "\"}").toList());
        return new StoredQuery(null, "{\"query\":{\"userConsents\":[" + values + "]}}", "rr", null, "3", null);
    }
}
