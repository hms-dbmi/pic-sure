package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import edu.harvard.dbmi.avillach.contracts.internal.SaveQueryRequest;
import edu.harvard.dbmi.avillach.contracts.internal.StoredQuery;
import edu.harvard.dbmi.avillach.contracts.internal.UpdateQueryRequest;
import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * Boots a real (H2) JPA context so save/get/update/dispatch are proven against an actual repository, not a mock.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(QueryPersistenceService.class)
class QueryPersistenceServiceTest {

    /** The canonical bare v3 body a row written since Task 15 carries -- no envelope, no credentials. */
    private static final String BARE_V3_QUERY = "{\"select\":[\"\\\\age\\\\\"],\"expectedResultType\":\"COUNT\"}";

    /** The same query as it was stored BEFORE Task 15: wrapped in the legacy QueryRequest envelope, credentials and all. */
    private static final String LEGACY_ENVELOPE_ROW = "{\"@type\":\"GeneralQueryRequest\","
        + "\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"}," + "\"query\":" + BARE_V3_QUERY + ",\"resourceUUID\":null}";

    @Autowired
    private QueryPersistenceService service;

    @Autowired
    private QueryRepository repo;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void saveThenGetRoundTripsTheGzipQueryStatusAndVersion() {
        SaveQueryRequest req = new SaveQueryRequest(
            "{\"select\":[\"foo\"]}", "resource-result-1", PicSureStatus.QUEUED, "3", Base64.getEncoder().encodeToString("meta".getBytes())
        );

        UUID picsureId = service.save(req);
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.picsureId()).isEqualTo(picsureId);
        assertThat(stored.query()).isEqualTo("{\"select\":[\"foo\"]}");
        assertThat(stored.resourceResultId()).isEqualTo("resource-result-1");
        assertThat(stored.status()).isEqualTo(PicSureStatus.QUEUED);
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
        UUID picsureId = service.save(new SaveQueryRequest("{\"q\":1}", "orig-result-id", PicSureStatus.QUEUED, "1", null));
        entityManager.flush();
        entityManager.clear();

        service.update(picsureId, new UpdateQueryRequest(PicSureStatus.AVAILABLE, null, null));
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.status()).isEqualTo(PicSureStatus.AVAILABLE);
        // resourceResultId/metadata were not part of the update -> unchanged.
        assertThat(stored.resourceResultId()).isEqualTo("orig-result-id");
        assertThat(stored.version()).isEqualTo("1");
    }

    @Test
    void updateUnknownIdThrowsNotFound() {
        UUID unknown = UUID.randomUUID();
        UpdateQueryRequest req = new UpdateQueryRequest(PicSureStatus.AVAILABLE, null, null);
        assertThatThrownBy(() -> service.update(unknown, req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(404));
    }

    // --- dispatch: one node shape out, whatever the row's age ---

    /** A row written since Task 15 is already bare; dispatch hands it back untouched. */
    @Test
    void dispatchOfABareRowReturnsTheStoredQueryUnchanged() {
        UUID picsureId = service.save(new SaveQueryRequest(BARE_V3_QUERY, null, PicSureStatus.QUEUED, "3", null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(picsureId)).isEqualTo(BARE_V3_QUERY);
    }

    /**
     * The whole point of normalizing at dispatch: an old envelope row and a new bare row carrying the SAME query must produce
     * byte-identical dispatch payloads, so the gateway's JsonPath authorization rules see one node shape regardless of when the row was
     * written.
     */
    @Test
    void dispatchOfALegacyEnvelopeRowReturnsTheSamePayloadAsABareRow() {
        UUID legacy = service.save(new SaveQueryRequest(LEGACY_ENVELOPE_ROW, null, PicSureStatus.QUEUED, "3", null));
        UUID bare = service.save(new SaveQueryRequest(BARE_V3_QUERY, null, PicSureStatus.QUEUED, "3", null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(legacy)).isEqualTo(service.dispatchQueryJson(bare)).isEqualTo(BARE_V3_QUERY);
    }

    /** The legacy envelope is where stored credentials actually live -- unwrapping must not be the only thing that removes them. */
    @Test
    void dispatchStripsResourceCredentialsFromALegacyEnvelopeRow() {
        UUID picsureId = service.save(new SaveQueryRequest(LEGACY_ENVELOPE_ROW, null, PicSureStatus.QUEUED, "3", null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(picsureId)).doesNotContain("resourceCredentials").doesNotContain("secret");
    }

    /** Defensive: credentials smuggled INSIDE the envelope's query member are stripped too, not carried out by the unwrap. */
    @Test
    void dispatchStripsResourceCredentialsNestedInsideTheEnvelopesQuery() {
        String row = "{\"resourceCredentials\":{\"BEARER_TOKEN\":\"outer\"},"
            + "\"query\":{\"expectedResultType\":\"COUNT\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"inner\"}}}";
        UUID picsureId = service.save(new SaveQueryRequest(row, null, PicSureStatus.QUEUED, "3", null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(picsureId)).doesNotContain("resourceCredentials").doesNotContain("outer")
            .doesNotContain("inner").isEqualTo("{\"expectedResultType\":\"COUNT\"}");
    }

    /**
     * A legacy row whose {@code query} member is not an object is not an envelope worth unwrapping -- the root is returned (credentials
     * stripped), exactly as before Task 15. Unwrapping it would hand the gateway a bare JSON string where it expects a query object.
     */
    @Test
    void dispatchOfARowWhoseQueryMemberIsNotAnObjectKeepsTheRoot() {
        String row = "{\"resourceUUID\":\"r\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}";
        UUID picsureId = service.save(new SaveQueryRequest(row, null, PicSureStatus.QUEUED, null, null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(picsureId)).doesNotContain("resourceCredentials").doesNotContain("secret")
            .contains("\"query\":\"q\"");
    }

    /** A {@code "query":null} row must not unwrap to a NullNode -- that would dispatch the literal string {@code "null"} to the gateway. */
    @Test
    void dispatchOfARowWithANullQueryMemberDoesNotEmitTheLiteralNull() {
        UUID picsureId = service
            .save(new SaveQueryRequest("{\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":null}", null, null, null, null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(picsureId)).isNotEqualTo("null").doesNotContain("resourceCredentials")
            .isEqualTo("{\"query\":null}");
    }

    @Test
    void dispatchUnknownIdThrowsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.dispatchQueryJson(unknown)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void dispatchOfBlankStoredQueryReturnsNull() {
        UUID picsureId = service.save(new SaveQueryRequest(null, null, PicSureStatus.QUEUED, null, null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(picsureId)).isNull();
    }

    @Test
    void dispatchOfAnUnparseableStoredQueryReturnsNullRatherThanTheRawBody() {
        UUID picsureId =
            service.save(new SaveQueryRequest("{\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"", null, null, null, null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(picsureId)).isNull();
    }

    @Test
    void invalidBase64MetadataOnSaveThrowsBadRequest() {
        SaveQueryRequest req = new SaveQueryRequest("{}", null, PicSureStatus.QUEUED, null, "not-valid-base64!!!");
        assertThatThrownBy(() -> service.save(req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void invalidBase64MetadataOnUpdateThrowsBadRequest() {
        UUID picsureId = service.save(new SaveQueryRequest("{}", null, PicSureStatus.QUEUED, null, null));
        entityManager.flush();
        entityManager.clear();

        UpdateQueryRequest req = new UpdateQueryRequest(null, null, "not-valid-base64!!!");
        assertThatThrownBy(() -> service.update(picsureId, req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void savedStatusEnumRoundTripsThroughStringMapping() {
        for (PicSureStatus status : PicSureStatus.values()) {
            UUID picsureId = service.save(new SaveQueryRequest("{}", null, status, null, null));
            entityManager.flush();
            entityManager.clear();

            assertThat(service.get(picsureId).status()).isEqualTo(status);
        }
    }

    /**
     * The mapping flip itself: {@code status} must reach the COLUMN as the enum NAME, not its ordinal. A round trip alone cannot tell the
     * two apart (either mapping round-trips), so this reads the raw column back. This is the in-code half of
     * {@code V9__ALTER_QUERY_STATUS_TO_STRING.sql}, which moves the existing MySQL rows the same way.
     */
    @Test
    void statusIsPersistedAsTheEnumNameNotItsOrdinal() {
        repo.deleteAll();
        for (PicSureStatus status : PicSureStatus.values()) {
            service.save(new SaveQueryRequest("{}", null, status, null, null));
        }
        entityManager.flush();
        entityManager.clear();

        @SuppressWarnings("unchecked")
        List<Object> persisted = entityManager.getEntityManager().createNativeQuery("select status from query").getResultList();

        assertThat(persisted).containsExactlyInAnyOrder("QUEUED", "PENDING", "ERROR", "AVAILABLE");
    }
}
