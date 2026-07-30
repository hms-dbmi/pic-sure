package edu.harvard.hms.dbmi.avillach.query.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;

/**
 * Ports the legacy WAR's {@code PicsureSearchServiceTest} coverage of {@code search}/{@code searchGenomicConceptValues} minus Resource +
 * AuditContext: backend resolution comes from the ingress {@code {backend}} path segment via {@link HpdsBackendSelector}, and both calls go
 * through {@link ResourceWebClient#search} / {@link ResourceWebClient#searchConceptValues}, which never receive an {@code Authorization}
 * header (verified indirectly here by asserting only the non-versioned base URL string is passed -- the client itself is the thing that
 * omits the token; see {@code ResourceWebClientTest} for that half of the parity guarantee).
 *
 * <p>The ingress contract is typed: {@link SearchRequest} in, {@link PaginatedResponse} out for concept values. The {@code QueryRequest}
 * envelope survives only on the downstream hop, which Task 7 retypes.
 */
class SearchServiceTest {

    ResourceWebClient hpds = mock(ResourceWebClient.class);
    HpdsProperties props = props();
    HpdsBackendSelector selector = new HpdsBackendSelector(props);
    SearchService service = new SearchService(hpds, selector);

    private static HpdsProperties props() {
        HpdsProperties p = new HpdsProperties();
        p.setAuthUrl("http://hpds/PIC-SURE");
        p.setOpenUrl("http://hpds/PIC-SURE");
        return p;
    }

    private SearchRequest req() {
        return new SearchRequest("BRCA");
    }

    @Test
    void searchUsesNonVersionedBase() {
        SearchResults sr = new SearchResults();
        when(hpds.search(eq("http://hpds/PIC-SURE"), any())).thenReturn(sr);

        assertThat(service.search("auth", req())).isSameAs(sr);

        verify(hpds).search(eq("http://hpds/PIC-SURE"), any()); // no /v3, no token param
    }

    /** The typed search term is what reaches HPDS -- the downstream envelope is built here, not accepted from the caller. */
    @Test
    void searchForwardsTheSearchTermDownstream() {
        service.search("auth", req());

        verify(hpds).search(eq("http://hpds/PIC-SURE"), argThat((QueryRequest r) -> "BRCA".equals(r.getQuery())));
    }

    @Test
    void searchResolvesOpenBackendSeparatelyFromAuth() {
        props.setOpenUrl("http://hpds-open/PIC-SURE");
        SearchResults sr = new SearchResults();
        when(hpds.search(eq("http://hpds-open/PIC-SURE"), any())).thenReturn(sr);

        service.search("open", req());

        verify(hpds).search(eq("http://hpds-open/PIC-SURE"), any());
    }

    @Test
    void searchRejectsNullRequest() {
        Assertions.assertThrows(PicsureException.class, () -> service.search("auth", null));
    }

    @Test
    void searchPropagatesHpdsUpstreamError() {
        when(hpds.search(any(), any())).thenThrow(new HpdsCommunicationException("boom"));

        Assertions.assertThrows(HpdsCommunicationException.class, () -> service.search("auth", req()));
    }

    @Test
    void valuesPassesParamsThroughOnNonVersionedBaseAndReturnsTheTypedPage() {
        // doReturn(): PaginatedSearchResult<?>'s captured wildcard makes when(...).thenReturn(...) uninferable here.
        doReturn(new PaginatedSearchResult<>(List.of("BRCA1", "BRCA2"), 1, 2)).when(hpds)
            .searchConceptValues(eq("http://hpds/PIC-SURE"), any(), any(), any(), any(), any());

        PaginatedResponse<String> out = service.searchConceptValues("auth", "\\gene\\", "BRCA", 1, 10);

        assertThat(out.results()).containsExactly("BRCA1", "BRCA2");
        assertThat(out.page()).isEqualTo(1);
        assertThat(out.total()).isEqualTo(2);
        verify(hpds).searchConceptValues(eq("http://hpds/PIC-SURE"), any(), eq("\\gene\\"), eq("BRCA"), eq(1), eq(10));
    }

    @Test
    void valuesReturnsAnEmptyPageWhenHpdsReturnsNothing() {
        when(hpds.searchConceptValues(any(), any(), any(), any(), any(), any())).thenReturn(null);

        PaginatedResponse<String> out = service.searchConceptValues("auth", "\\gene\\", "BRCA", null, null);

        assertThat(out.results()).isEmpty();
        assertThat(out.total()).isZero();
    }

    @Test
    void valuesPropagatesHpdsUpstreamError() {
        when(hpds.searchConceptValues(any(), any(), any(), any(), any(), any())).thenThrow(new HpdsCommunicationException("boom"));

        Assertions.assertThrows(HpdsCommunicationException.class, () -> service.searchConceptValues("auth", "\\gene\\", "BRCA", 1, 10));
    }

    @Test
    void unknownBackendIsRejectedBeforeReachingHpds() {
        Assertions.assertThrows(PicsureException.class, () -> service.search("bogus", req()));

        verify(hpds, never()).search(any(), any());
    }
}
