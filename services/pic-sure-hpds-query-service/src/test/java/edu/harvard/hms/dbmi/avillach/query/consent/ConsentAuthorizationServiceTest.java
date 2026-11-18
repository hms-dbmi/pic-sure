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
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

class ConsentAuthorizationServiceTest {

    @Test
    void authQueryIsReplacedWithCallerScopedQuery() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Map.of("\\_consents\\", Set.of("phs001.c1")));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(
            new Query(
                List.of(), List.of(new AuthorizationFilter("\\attacker\\", Set.of("grant"))), null, List.of(), ResultType.COUNT, null, null
            )
        );

        service.scopeQuery("auth", request, "Bearer caller-token");

        Query scoped = (Query) request.getQuery();
        assertThat(scoped.authorizationFilters()).containsExactly(new AuthorizationFilter("\\_consents\\", Set.of("phs001.c1")));
    }

    @Test
    void disabledConsentAuthorizationLeavesQueryUntouched() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), false);
        Query query = new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, null, null);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(query);

        service.scopeQuery("auth", request, "Bearer caller-token");

        assertThat(request.getQuery()).isSameAs(query);
        verifyNoInteractions(client);
    }

    @Test
    void disabledConsentAuthorizationLeavesSavedQueryReadsUntouched() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), false);

        service.verifyReadAccess("auth", storedWithFilters("\\_consents\\", "phs001.c1"), null);

        verifyNoInteractions(client);
    }

    @Test
    void openBackendLeavesQueryUntouched() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);
        Query query = new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, null, null);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(query);

        service.scopeQuery("open", request, "Bearer caller-token");

        assertThat(request.getQuery()).isSameAs(query);
        verifyNoInteractions(client);
    }

    @Test
    void queryMapFromHttpBindingIsConvertedToV3QueryBeforeScoping() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Map.of("\\_consents\\", Set.of("phs001.c1")));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);
        GeneralQueryRequest request =
            new GeneralQueryRequest().setQuery(Map.of("select", List.of("\\Demographics\\Age\\"), "expectedResultType", "COUNT"));

        service.scopeQuery("auth", request, "Bearer caller-token");

        assertThat(request.getQuery()).isInstanceOf(Query.class);
        assertThat(((Query) request.getQuery()).authorizationFilters())
            .containsExactly(new AuthorizationFilter("\\_consents\\", Set.of("phs001.c1")));
    }

    /**
     * Scoping is keyed on the {@code auth} path segment, never on the body naming a result type. A query missing one is still consent
     * filtered rather than forwarded unscoped, which is what lets the v3 model accept it.
     */
    @Test
    void authQueryWithoutExpectedResultTypeIsStillScoped() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Map.of("\\_consents\\", Set.of("phs001.c1")));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(Map.of("select", List.of("\\phs000999\\variable\\")));

        service.scopeQuery("auth", request, "Bearer caller-token");

        Query scoped = (Query) request.getQuery();
        assertThat(scoped.expectedResultType()).isNull();
        assertThat(scoped.authorizationFilters()).containsExactly(new AuthorizationFilter("\\_consents\\", Set.of("phs001.c1")));
    }

    @Test
    void authQueryWithoutCallerTokenFailsClosed() {
        ConsentAuthorizationService service =
            new ConsentAuthorizationService(mock(PsamaConsentClient.class), new ConsentFilterBuilder(), true);
        GeneralQueryRequest request =
            new GeneralQueryRequest().setQuery(new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, null, null));

        assertThatThrownBy(() -> service.scopeQuery("auth", request, null)).isInstanceOfSatisfying(PicsureException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.getErrorType()).isEqualTo("consent_lookup_failed");
        });
    }

    @Test
    void savedConsentValuesThatRemainASubsetAreAllowed() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Map.of("\\_consents\\", Set.of("phs001.c1", "phs002.c1")));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);

        service.verifyReadAccess("auth", storedWithFilters("\\_consents\\", "phs001.c1"), "Bearer caller-token");
    }

    @Test
    void lostSavedConsentValueIsDenied() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Map.of("\\_consents\\", Set.of("phs001.c1")));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);

        assertThatThrownBy(() -> service.verifyReadAccess("auth", storedWithFilters("\\_consents\\", "phs002.c1"), "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getErrorType()).isEqualTo("consent_denied");
            });
    }

    @Test
    void consentValuesAreComparedUnderTheirSavedConceptPath() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Map.of("\\_consents\\", Set.of("phs001.c1")));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);

        assertThatThrownBy(
            () -> service.verifyReadAccess("auth", storedWithFilters("\\_topmed_consents\\", "phs001.c1"), "Bearer caller-token")
        ).isInstanceOfSatisfying(PicsureException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void storedQueryWithoutAuthorizationFiltersIsDenied() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Map.of("\\_consents\\", Set.of("phs001.c1")));
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);
        StoredQuery stored = new StoredQuery(null, "{\"query\":{\"expectedResultType\":\"COUNT\"}}", "rr", null, "3", null);

        assertThatThrownBy(() -> service.verifyReadAccess("auth", stored, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static StoredQuery storedWithFilters(String conceptPath, String value) {
        String query = "{\"query\":{\"authorizationFilters\":[{\"conceptPath\":\"" + conceptPath.replace("\\", "\\\\") + "\",\"values\":[\""
            + value + "\"]}]}}";
        return new StoredQuery(null, query, "rr", null, "3", null);
    }

    @Test
    void emptyConsentsFromPsamaIs403ConsentDenied() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(Map.of());
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);
        GeneralQueryRequest request =
            new GeneralQueryRequest().setQuery(new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, null, null));

        assertThatThrownBy(() -> service.currentConsents("auth", "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getErrorType()).isEqualTo("consent_denied");
            });
        assertThatThrownBy(() -> service.scopeQuery("auth", request, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getErrorType()).isEqualTo("consent_denied");
            });
    }

    @Test
    void currentConsentsAreSortedByConceptAndByValue() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        when(client.fetch("Bearer caller-token")).thenReturn(
            Map.of("\\_topmed_consents\\", Set.of("phs002.c1"), "\\_consents\\", Set.of("phs002.c1", "phs001.c2", "phs001.c1"))
        );
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);

        Map<String, List<String>> consents = service.currentConsents("auth", "Bearer caller-token");

        assertThat(consents.keySet()).containsExactly("\\_consents\\", "\\_topmed_consents\\");
        assertThat(consents.get("\\_consents\\")).containsExactly("phs001.c1", "phs001.c2", "phs002.c1");
    }

    @Test
    void currentConsentsAreEmptyWithoutALookupWhenConsentAuthorizationIsOffOrTheBackendIsNotAuth() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);

        assertThat(
            new ConsentAuthorizationService(client, new ConsentFilterBuilder(), false).currentConsents("auth", "Bearer caller-token")
        ).isEmpty();
        assertThat(new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true).currentConsents("open", "Bearer caller-token"))
            .isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void currentConsentsWithoutCallerTokenFailClosed() {
        ConsentAuthorizationService service =
            new ConsentAuthorizationService(mock(PsamaConsentClient.class), new ConsentFilterBuilder(), true);

        assertThatThrownBy(() -> service.currentConsents("auth", " ")).isInstanceOfSatisfying(PicsureException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.getErrorType()).isEqualTo("consent_lookup_failed");
        });
    }

    @Test
    void scopingWithConsentsAlreadyFetchedBuildsTheSameFiltersWithoutASecondLookup() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        ConsentAuthorizationService service = new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true);
        GeneralQueryRequest request =
            new GeneralQueryRequest().setQuery(Map.of("select", List.of("\\Demographics\\Age\\"), "expectedResultType", "COUNT"));

        service.scopeWithConsents("auth", request, Map.of("\\_consents\\", List.of("phs001.c1", "phs001.c2")));

        assertThat(((Query) request.getQuery()).authorizationFilters())
            .containsExactly(new AuthorizationFilter("\\_consents\\", Set.of("phs001.c1", "phs001.c2")));
        verifyNoInteractions(client);
    }

    @Test
    void scopingWithConsentsLeavesTheQueryUntouchedWhenConsentAuthorizationIsOffOrTheBackendIsNotAuth() {
        PsamaConsentClient client = mock(PsamaConsentClient.class);
        Query query = new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, null, null);
        GeneralQueryRequest request = new GeneralQueryRequest().setQuery(query);

        new ConsentAuthorizationService(client, new ConsentFilterBuilder(), false).scopeWithConsents("auth", request, Map.of());
        new ConsentAuthorizationService(client, new ConsentFilterBuilder(), true).scopeWithConsents("open", request, Map.of());

        assertThat(request.getQuery()).isSameAs(query);
    }
}
