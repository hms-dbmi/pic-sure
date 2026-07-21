package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;

class NamedDatasetMapperTest {

    private final NamedDatasetMapper mapper = new NamedDatasetMapper();

    @Test
    void toDtoCopiesAllFieldsIncludingTheNestedQuery() {
        UUID datasetId = UUID.randomUUID();
        UUID queryId = UUID.randomUUID();
        Query query = new Query();
        query.setUuid(queryId);
        query.setQuery("{\"query\":{}}");
        query.setStartTime(new Date(1690000000000L));
        query.setStatus(PicSureStatus.AVAILABLE);
        NamedDataset entity =
            new NamedDataset().setUser("alice@example.com").setName("d1").setQuery(query).setArchived(true).setMetadata(Map.of("k", "v"));
        entity.setUuid(datasetId);

        NamedDatasetDto dto = mapper.toDto(entity);

        assertThat(dto.uuid()).isEqualTo(datasetId);
        assertThat(dto.user()).isEqualTo("alice@example.com");
        assertThat(dto.name()).isEqualTo("d1");
        assertThat(dto.archived()).isTrue();
        assertThat(dto.metadata()).containsEntry("k", "v");
        assertThat(dto.query().uuid()).isEqualTo(queryId);
        assertThat(dto.query().query()).isEqualTo("{\"query\":{}}");
        assertThat(dto.query().startTime()).isEqualTo(1690000000000L);
        assertThat(dto.query().status()).isEqualTo(PicSureStatus.AVAILABLE);
    }

    /** An un-run query has no start time; the wire value must be null rather than blowing up on {@code Date#getTime()}. */
    @Test
    void toDtoHandlesQueryWithoutStartTime() {
        Query query = new Query();
        query.setUuid(UUID.randomUUID());
        NamedDataset entity = new NamedDataset().setUser("alice@example.com").setName("d1").setQuery(query);
        entity.setUuid(UUID.randomUUID());

        NamedDatasetDto dto = mapper.toDto(entity);

        assertThat(dto.query().startTime()).isNull();
        assertThat(dto.query().query()).isEmpty();
    }

    @Test
    void toDtoHandlesNullQuery() {
        NamedDataset entity = new NamedDataset().setUser("alice@example.com").setName("d1").setArchived(false);
        entity.setUuid(UUID.randomUUID());

        NamedDatasetDto dto = mapper.toDto(entity);

        assertThat(dto.query()).isNull();
    }

    @Test
    void toEntityBuildsEntityFromRequestWithResolvedQueryAndUser() {
        UUID queryId = UUID.randomUUID();
        Query query = new Query();
        query.setUuid(queryId);
        NamedDatasetRequestDto req = new NamedDatasetRequestDto(queryId, "d2", true, Map.of("a", 1));

        NamedDataset entity = mapper.toEntity("bob@example.com", query, req);

        assertThat(entity.getUser()).isEqualTo("bob@example.com");
        assertThat(entity.getQuery()).isSameAs(query);
        assertThat(entity.getName()).isEqualTo("d2");
        assertThat(entity.getArchived()).isTrue();
        assertThat(entity.getMetadata()).containsEntry("a", 1);
    }
}
