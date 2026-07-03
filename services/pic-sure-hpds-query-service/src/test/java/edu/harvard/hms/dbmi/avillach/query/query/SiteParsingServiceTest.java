package edu.harvard.hms.dbmi.avillach.query.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * TDD port of the legacy WAR's {@code SiteParsingService} (institutional/GIC site-of-origin), DB-free: the institution-lookup-by-domain
 * call that used to hit a local {@code SiteRepository} now goes over HTTP via {@link OperationsClient#findSitesByDomain(String)} instead,
 * matching the module's "no database" mandate.
 */
class SiteParsingServiceTest {

    OperationsClient client = mock(OperationsClient.class);
    SiteParsingService service = new SiteParsingService(client);

    @Test
    void parsesDomainToSiteCode() {
        when(client.findSitesByDomain("harvard.edu")).thenReturn(List.of("HARVARD"));

        assertThat(service.parseSiteOfOrigin("alice@harvard.edu")).contains("HARVARD");
    }

    @Test
    void emptyWhenNoMatch() {
        when(client.findSitesByDomain("nowhere.com")).thenReturn(List.of());

        assertThat(service.parseSiteOfOrigin("bob@nowhere.com")).isEmpty();
    }

    @Test
    void emptyWhenMultipleMatches() {
        when(client.findSitesByDomain("shared.edu")).thenReturn(List.of("HARVARD", "MIT"));

        assertThat(service.parseSiteOfOrigin("carol@shared.edu")).isEmpty();
    }

    @Test
    void emptyForUnparsableEmail() {
        assertThat(service.parseSiteOfOrigin("not-an-email")).isEmpty();
    }

    @Test
    void emptyForNullEmail() {
        assertThat(service.parseSiteOfOrigin(null)).isEmpty();
    }
}
