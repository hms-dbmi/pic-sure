package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;

class NamedDatasetMapperTest {

    private final NamedDatasetMapper mapper = new NamedDatasetMapper();

    @Test
    void toDtoCopiesAllFieldsIncludingTheNestedQuery() throws JsonProcessingException {
        UUID datasetId = UUID.randomUUID();
        UUID queryId = UUID.randomUUID();
        Query query = new Query();
        query.setUuid(queryId);
        query.setQuery("{\"categoryFilters\":{}}");
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
        assertThat(
            new ObjectMapper().treeToValue(
                new ObjectMapper().readTree(dto.query().query()).get("query"), edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class
            )
        ).isNotNull();
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
    void toDtoConvertsV1Query() throws JsonProcessingException {
        Query query = new Query();
        query.setUuid(UUID.randomUUID());
        query.setQuery("{\"categoryFilters\":{}}");
        NamedDataset entity = new NamedDataset().setUser("alice@example.com").setName("d1").setQuery(query);
        entity.setUuid(UUID.randomUUID());

        NamedDatasetDto dto = mapper.toDto(entity);

        assertThat(
            new ObjectMapper().treeToValue(
                new ObjectMapper().readTree(dto.query().query()).get("query"), edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class
            )
        ).isNotNull();
    }


    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LEGACY = """
        {"categoryFilters":{"sex":["Female"]},"fields":["age"],"expectedResultType":"DATAFRAME"}
        """;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2", "1.0"})
    void translatesTheInnerLegacyQueryAndPreservesTheWrapper(String version) throws Exception {
        String stored = "{\"query\":" + LEGACY + ",\"resourceUUID\":\"resource-id\",\"commonAreaUUID\":\"common-id\"}";
        Query query = new Query().setQuery(stored).setVersion(version);
        String original = query.getQuery();
        JsonNode result = JSON.readTree(mapper.toDto(new NamedDataset().setQuery(query)).query().query());

        assertConvertedQuery(result.get("query"));
        assertThat(result.path("resourceUUID").asText()).isEqualTo("resource-id");
        assertThat(result.path("commonAreaUUID").asText()).isEqualTo("common-id");
        assertThat(query.getQuery()).isEqualTo(original);
        assertThat(query.getVersion()).isEqualTo(version);
    }

    @Test
    void translatesAStringEncodedInnerLegacyQuery() throws Exception {
        JsonNode result = mapped("{\"query\":" + JSON.writeValueAsString(LEGACY) + "}", "2");
        assertConvertedQuery(result.get("query"));
    }

    @Test
    void wrapsAndTranslatesABareLegacyQuery() throws Exception {
        assertConvertedQuery(mapped(LEGACY, "2").get("query"));
    }

    private static void assertConvertedQuery(JsonNode inner) {
        assertThat(inner).isNotNull();
        assertThat(inner.path("select")).isEqualTo(JSON.valueToTree(java.util.List.of("age")));
        assertThat(inner.path("expectedResultType").asText()).isEqualTo("DATAFRAME");
        assertThat(inner.at("/phenotypicClause/conceptPath").asText()).isEqualTo("sex");
        assertThat(inner.at("/phenotypicClause/values")).isEqualTo(JSON.valueToTree(java.util.List.of("Female")));
        assertThat(inner.has("categoryFilters")).isFalse();
    }

    private static final String V3 = """
        {"select":["age"],"authorizationFilters":[],"phenotypicClause":null,
        "genomicFilters":[{"key":"Gene","values":["BRCA1"],"min":null,"max":null}],
        "expectedResultType":"DATAFRAME","picsureId":null,"id":null}
        """;

    @ParameterizedTest
    @ValueSource(strings = {"3", "3.0.0"})
    void preservesV3ContentsAndProvidesAWrapper(String version) throws Exception {
        for (String stored : java.util.List.of(V3, "{\"query\":" + V3 + "}", "{\"query\":" + JSON.writeValueAsString(V3) + "}")) {
            assertThat(mapped(stored, version).get("query")).isEqualTo(JSON.readTree(V3));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1.0", "2", "4", "."})
    void treatsEveryNonV3VersionAsV2EvenWithV3NamedExtraFields(String version) throws Exception {
        ObjectNode legacy = (ObjectNode) JSON.readTree(LEGACY);
        legacy.set("select", JSON.valueToTree(java.util.List.of("unrelated-extra-field")));
        legacy.putNull("phenotypicClause");
        assertConvertedQuery(mapped(JSON.writeValueAsString(legacy), version).get("query"));
    }

    @Test
    void trustsTheV3VersionWithoutRequiringRecognizedFieldNames() throws Exception {
        JsonNode result = mapped("{\"query\":{\"historical\":true}}", "3");
        assertThat(result.at("/query/historical").asBoolean()).isTrue();
        assertThat(result.path("query").has("phenotypicClause")).isTrue();
        assertThat(result.at("/query/phenotypicClause").isNull()).isTrue();
    }

    @Test
    void wrapsTheEmptyV3ResponseReportedByTheFrontend() throws Exception {
        String emptyV3 = """
            {"select":[],"authorizationFilters":[],"phenotypicClause":null,"genomicFilters":[],
            "expectedResultType":"COUNT","picsureId":null,"id":null}
            """;
        assertThat(mapped(emptyV3, "3").get("query")).isEqualTo(JSON.readTree(emptyV3));
    }

    @Test
    void acceptsAnExplicitlyVersionedV3CountWithOmittedFiltersAndSelections() throws Exception {
        JsonNode result = mapped("{\"query\":{\"expectedResultType\":\"COUNT\"}}", "3");
        assertThat(result.at("/query/expectedResultType").asText()).isEqualTo("COUNT");
        assertThat(result.path("query").has("phenotypicClause")).isTrue();
        assertThat(result.at("/query/phenotypicClause").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "3"})
    void doesNotReturnStoredRequestCredentials(String version) throws Exception {
        String inner = version.equals("3") ? V3 : LEGACY;
        String credentials = "\"resourceCredentials\":{\"BEARER_TOKEN\":\"test-secret\"},";
        for (
            String stored : java.util.List
                .of("{" + credentials + "\"query\":" + inner + "}", "{" + credentials + inner.strip().substring(1))
        ) {
            JsonNode result = mapped(stored, version);
            assertThat(result.has("resourceCredentials")).isFalse();
            assertThat(result.path("query").has("resourceCredentials")).isFalse();
            assertThat(result.path("query").isObject()).isTrue();
        }
    }

    @Test
    void rejectsMalformedV3FieldTypes() {
        assertThatThrownBy(() -> mapped("{\"query\":{\"select\":{}}}", "3")).isInstanceOf(PicsureException.class);
    }

    @Test
    void preservesHistoricalExtraV3Fields() throws Exception {
        String stored = "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"historical\":\"value\"}}";
        assertThat(mapped(stored, "3").at("/query/historical").asText()).isEqualTo("value");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"not json", "null", "[]", "{}", "{\"unrelated\":true}", "{\"query\":null}", "{\"query\":[]}", "{\"query\":\"invalid\"}",
            "{\"query\":{\"unrelated\":true}}", "{\"query\":{\"categoryFilters\":\"invalid\"}}"}
    )
    void rejectsUnknownOrMalformedQueriesInsteadOfReturningAnEmptyCohort(String stored) {
        assertThatThrownBy(() -> mapped(stored, "2")).isInstanceOf(PicsureException.class);
    }

    @Test
    void rejectsUntranslatableGenomicGroups() {
        String stored = """
            {"query":{"variantInfoFilters":[
            {"categoryVariantInfoFilters":{"Gene":["BRCA1"]}},
            {"categoryVariantInfoFilters":{"Gene":["BRCA2"]}}]}}
            """;
        assertThatThrownBy(() -> mapped(stored, "2")).isInstanceOf(PicsureException.class);
    }

    private JsonNode mapped(String stored, String version) throws Exception {
        return JSON.readTree(mapper.toDto(new NamedDataset().setQuery(new Query().setQuery(stored).setVersion(version))).query().query());
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
