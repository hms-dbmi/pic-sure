package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * Boots a real (H2) JPA context so save/get/update/dispatch are proven against an actual repository, not a mock.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(QueryPersistenceService.class)
class QueryPersistenceServiceTest {

    @Autowired
    private QueryPersistenceService service;

    @Autowired
    private QueryRepository repo;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void saveThenGetRoundTripsTheGzipQueryStatusAndVersion() {
        SaveQueryRequest req = new SaveQueryRequest(
            "{\"select\":[\"foo\"]}", "resource-result-1", "QUEUED", "3", Base64.getEncoder().encodeToString("meta".getBytes())
        );

        UUID picsureId = service.save(req);
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.picsureId()).isEqualTo(picsureId);
        assertThat(stored.query()).isEqualTo("{\"select\":[\"foo\"]}");
        assertThat(stored.resourceResultId()).isEqualTo("resource-result-1");
        assertThat(stored.status()).isEqualTo("QUEUED");
        assertThat(stored.version()).isEqualTo("3");
        assertThat(new String(Base64.getDecoder().decode(stored.metadata()))).isEqualTo("meta");
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.get(unknown)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void updateChangesOnlyThePresentFields() {
        UUID picsureId = service.save(new SaveQueryRequest("{\"q\":1}", "orig-result-id", "QUEUED", "1", null));
        entityManager.flush();
        entityManager.clear();

        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null));
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.status()).isEqualTo("AVAILABLE");
        // resourceResultId/metadata were not part of the update -> unchanged.
        assertThat(stored.resourceResultId()).isEqualTo("orig-result-id");
        assertThat(stored.version()).isEqualTo("1");
    }

    @Test
    void updateUnknownIdThrowsNotFound() {
        UUID unknown = UUID.randomUUID();
        UpdateQueryRequest req = new UpdateQueryRequest("AVAILABLE", null, null);
        assertThatThrownBy(() -> service.update(unknown, req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void dispatchStripsResourceCredentialsAndReturnsAString() {
        UUID picsureId = service.save(
            new SaveQueryRequest(
                "{\"resourceUUID\":\"r\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}", null, "QUEUED", null, null
            )
        );
        entityManager.flush();
        entityManager.clear();

        String dispatchJson = service.dispatchQueryJson(picsureId);

        assertThat(dispatchJson).doesNotContain("resourceCredentials").doesNotContain("secret").contains("\"query\":\"q\"");
    }

    @Test
    void dispatchUnknownIdThrowsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.dispatchQueryJson(unknown)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void dispatchOfBlankStoredQueryReturnsNull() {
        UUID picsureId = service.save(new SaveQueryRequest(null, null, "QUEUED", null, null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(picsureId)).isNull();
    }

    @Test
    void invalidStatusOnSaveThrowsBadRequest() {
        SaveQueryRequest req = new SaveQueryRequest("{}", null, "NOT_A_REAL_STATUS", null, null);
        assertThatThrownBy(() -> service.save(req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void invalidBase64MetadataOnSaveThrowsBadRequest() {
        SaveQueryRequest req = new SaveQueryRequest("{}", null, "QUEUED", null, "not-valid-base64!!!");
        assertThatThrownBy(() -> service.save(req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void invalidBase64MetadataOnUpdateThrowsBadRequest() {
        UUID picsureId = service.save(new SaveQueryRequest("{}", null, "QUEUED", null, null));
        entityManager.flush();
        entityManager.clear();

        UpdateQueryRequest req = new UpdateQueryRequest(null, null, "not-valid-base64!!!");
        assertThatThrownBy(() -> service.update(picsureId, req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void savedStatusEnumRoundTripsThroughOrdinalMapping() {
        for (PicSureStatus status : PicSureStatus.values()) {
            UUID picsureId = service.save(new SaveQueryRequest("{}", null, status.name(), null, null));
            entityManager.flush();
            entityManager.clear();

            assertThat(service.get(picsureId).status()).isEqualTo(status.name());
        }
    }
}
