package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.operations.query.Query;

class NamedDatasetMapperTest {

    private final NamedDatasetMapper mapper = new NamedDatasetMapper();

    @Test
    void toDtoCopiesAllFieldsIncludingQueryId() {
        UUID datasetId = UUID.randomUUID();
        UUID queryId = UUID.randomUUID();
        Query query = new Query();
        query.setUuid(queryId);
        NamedDataset entity =
            new NamedDataset().setUser("alice@example.com").setName("d1").setQuery(query).setArchived(true).setMetadata(Map.of("k", "v"));
        entity.setUuid(datasetId);

        NamedDatasetDto dto = mapper.toDto(entity);

        assertThat(dto.uuid()).isEqualTo(datasetId);
        assertThat(dto.name()).isEqualTo("d1");
        assertThat(dto.queryId()).isEqualTo(queryId);
        assertThat(dto.archived()).isTrue();
        assertThat(dto.metadata()).containsEntry("k", "v");
    }

    @Test
    void toDtoHandlesNullQuery() {
        NamedDataset entity = new NamedDataset().setUser("alice@example.com").setName("d1").setArchived(false);
        entity.setUuid(UUID.randomUUID());

        NamedDatasetDto dto = mapper.toDto(entity);

        assertThat(dto.queryId()).isNull();
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
