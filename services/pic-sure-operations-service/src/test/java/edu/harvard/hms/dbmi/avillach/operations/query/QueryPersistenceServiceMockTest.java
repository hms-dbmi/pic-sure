package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * Covers {@link QueryPersistenceService#findByCommonAreaUUID(UUID)} against a mocked {@link QueryRepository}. The real
 * {@code getQueryUUIDFromCommonAreaUUID} lookup runs a MySQL-only native query ({@code CONVERT(... USING
 * utf8)}) that H2 cannot parse (see the repository's javadoc and {@code RepositorySmokeTest}'s disabled tests for that same query) -- a
 * plain Mockito unit test proves this service's wiring/mapping/404 path without needing a real database.
 */
class QueryPersistenceServiceMockTest {

    private final QueryRepository repo = mock(QueryRepository.class);
    private final QueryPersistenceService service = new QueryPersistenceService(repo);

    @Test
    void findByCommonAreaUUIDMapsTheMatchedEntity() {
        UUID picsureId = UUID.randomUUID();
        UUID commonAreaUUID = UUID.randomUUID();
        Query stored = new Query();
        stored.setUuid(picsureId);
        stored.setQuery("{\"select\":[\"foo\"]}");
        stored.setStatus(PicSureStatus.AVAILABLE);
        when(repo.getQueryUUIDFromCommonAreaUUID(commonAreaUUID)).thenReturn(stored);

        StoredQuery found = service.findByCommonAreaUUID(commonAreaUUID);

        assertThat(found.picsureId()).isEqualTo(picsureId);
        assertThat(found.query()).isEqualTo("{\"select\":[\"foo\"]}");
        assertThat(found.status()).isEqualTo("AVAILABLE");
    }

    @Test
    void findByCommonAreaUUIDThrowsNotFoundWhenNoRowMatches() {
        UUID commonAreaUUID = UUID.randomUUID();
        when(repo.getQueryUUIDFromCommonAreaUUID(commonAreaUUID)).thenReturn(null);

        assertThatThrownBy(() -> service.findByCommonAreaUUID(commonAreaUUID)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(404));
    }
}
