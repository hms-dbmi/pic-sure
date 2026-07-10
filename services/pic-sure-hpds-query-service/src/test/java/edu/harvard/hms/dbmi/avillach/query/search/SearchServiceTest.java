package edu.harvard.hms.dbmi.avillach.query.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
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

    private QueryRequest req() {
        GeneralQueryRequest r = new GeneralQueryRequest();
        r.setQuery("BRCA");
        return r;
    }

    @Test
    void searchUsesNonVersionedBase() {
        SearchResults sr = new SearchResults();
        when(hpds.search(eq("http://hpds/PIC-SURE"), any())).thenReturn(sr);

        assertThat(service.search("auth", req())).isSameAs(sr);

        verify(hpds).search(eq("http://hpds/PIC-SURE"), any()); // no /v3, no token param
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
    void valuesPassesParamsThroughOnNonVersionedBase() {
        service.searchConceptValues("auth", req(), "\\gene\\", "BRCA", 1, 10);

        verify(hpds).searchConceptValues(eq("http://hpds/PIC-SURE"), any(), eq("\\gene\\"), eq("BRCA"), eq(1), eq(10));
    }

    @Test
    void valuesPropagatesHpdsUpstreamError() {
        when(hpds.searchConceptValues(any(), any(), any(), any(), any(), any())).thenThrow(new HpdsCommunicationException("boom"));

        Assertions.assertThrows(
            HpdsCommunicationException.class, () -> service.searchConceptValues("auth", req(), "\\gene\\", "BRCA", 1, 10)
        );
    }

    @Test
    void unknownBackendIsRejectedBeforeReachingHpds() {
        Assertions.assertThrows(PicsureException.class, () -> service.search("bogus", req()));

        verify(hpds, never()).search(any(), any());
    }
}
