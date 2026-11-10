package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        query.setStartTime(Instant.ofEpochMilli(1690000000000L));
        query.setStatus(PicSureStatus.AVAILABLE);
        NamedDataset entity =
            new NamedDataset().setUser("alice@example.com").setName("d1").setQuery(query).setArchived(true).setMetadata(Map.of("k", "v"));
        entity.setUuid(datasetId);

        NamedDatasetDto dto = mapper.toDto(entity, List.of());

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

    /** An un-run query has no start time; the wire value must be null rather than blowing up on {@code Instant#toEpochMilli()}. */
    @Test
    void toDtoHandlesQueryWithoutStartTime() {
        Query query = new Query();
        query.setUuid(UUID.randomUUID());
        NamedDataset entity = new NamedDataset().setUser("alice@example.com").setName("d1").setQuery(query);
        entity.setUuid(UUID.randomUUID());

        NamedDatasetDto dto = mapper.toDto(entity, List.of());

        assertThat(dto.query().startTime()).isNull();
        assertThat(dto.query().query()).isEmpty();
    }

    @Test
    void toDtoHandlesNullQuery() {
        NamedDataset entity = new NamedDataset().setUser("alice@example.com").setName("d1").setArchived(false);
        entity.setUuid(UUID.randomUUID());

        NamedDatasetDto dto = mapper.toDto(entity, List.of());

        assertThat(dto.query()).isNull();
    }

    @Test
    void toDtoConvertsV1Query() throws JsonProcessingException {
        Query query = new Query();
        query.setUuid(UUID.randomUUID());
        query.setQuery("{\"categoryFilters\":{}}");
        NamedDataset entity = new NamedDataset().setUser("alice@example.com").setName("d1").setQuery(query);
        entity.setUuid(UUID.randomUUID());

        NamedDatasetDto dto = mapper.toDto(entity, List.of());

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
        JsonNode result = JSON.readTree(mapper.toDto(new NamedDataset().setQuery(query), List.of()).query().query());

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

    @Test
    void preservesV3ContentsAndProvidesAWrapper() throws Exception {
        for (String stored : java.util.List.of(V3, "{\"query\":" + V3 + "}", "{\"query\":" + JSON.writeValueAsString(V3) + "}")) {
            assertThat(mapped(stored, "3").get("query")).isEqualTo(JSON.readTree(V3));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1.0", "2", "3.0", "3.0.0", "4", "."})
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
        return JSON.readTree(
            mapper.toDto(new NamedDataset().setQuery(new Query().setQuery(stored).setVersion(version)), List.of()).query().query()
        );
    }

    private static final String CONSENTS = "\\_consents\\";
    private static final String TOPMED_CONSENTS = "\\_topmed_consents\\";
    private static final String HARMONIZED_CONSENT = "\\_harmonized_consent\\";
    private static final String PARENT_CONSENTS = "\\_parent_consents\\";
    private static final String SEX = "\\demographics\\SEX\\";
    private static final String AGE = "\\demographics\\AGE\\";
    private static final String STUDY_CONSENT = "phs000001.c1";
    private static final String TOPMED_CONSENT = "phs000002.c2";
    private static final String HARMONIZED_STUDY_CONSENT = "phs000003.c1";

    private static ObjectNode filter(String conceptPath, String value) {
        ObjectNode filter = JSON.createObjectNode();
        filter.put("phenotypicFilterType", "FILTER");
        filter.put("conceptPath", conceptPath);
        filter.putArray("values").add(value);
        return filter;
    }

    private static ObjectNode group(String operator, JsonNode... clauses) {
        ObjectNode group = JSON.createObjectNode();
        ArrayNode members = group.putArray("phenotypicClauses");
        for (JsonNode clause : clauses) {
            members.add(clause);
        }
        group.put("operator", operator);
        return group;
    }

    private static ObjectNode v3QueryWithServerBuiltFilters(JsonNode clause) {
        ObjectNode query = JSON.createObjectNode();
        query.putArray("select").add(AGE);
        ArrayNode authorizationFilters = query.putArray("authorizationFilters");
        authorizationFilters.addObject().put("conceptPath", CONSENTS).putArray("values").add(STUDY_CONSENT);
        authorizationFilters.addObject().put("conceptPath", TOPMED_CONSENTS).putArray("values").add(TOPMED_CONSENT);
        authorizationFilters.addObject().put("conceptPath", HARMONIZED_CONSENT).putArray("values").add(HARMONIZED_STUDY_CONSENT);
        query.set("phenotypicClause", clause);
        query.putArray("genomicFilters");
        query.put("expectedResultType", "DATAFRAME");
        return query;
    }

    private static String enveloped(JsonNode query) {
        return JSON.createObjectNode().<ObjectNode>set("query", query).toString();
    }

    private static void assertCarriesNoConsentValue(JsonNode returned) {
        assertThat(returned.toString()).doesNotContain(STUDY_CONSENT, TOPMED_CONSENT, HARMONIZED_STUDY_CONSENT);
    }

    @Test
    void neverReturnsAuthorizationFiltersForAnOldRow() throws Exception {
        JsonNode result = mapped(enveloped(v3QueryWithServerBuiltFilters(filter(SEX, "Male"))), "3");

        assertThat(result.at("/query/authorizationFilters").isArray()).isTrue();
        assertThat(result.at("/query/authorizationFilters")).isEmpty();
        assertThat(result.at("/query/phenotypicClause")).isEqualTo(filter(SEX, "Male"));
        assertCarriesNoConsentValue(result);
    }

    @Test
    void neverReturnsAuthorizationFiltersForANewRow() throws Exception {
        Query owned = new Query().setQuery(enveloped(v3QueryWithServerBuiltFilters(filter(SEX, "Male")))).setVersion("3");
        owned.setUuid(UUID.randomUUID());
        owned.setUserUuid(UUID.randomUUID());
        owned.setConsents(Map.of(CONSENTS, List.of(STUDY_CONSENT)));

        NamedDatasetDto dto = mapper.toDto(new NamedDataset().setQuery(owned), List.of());

        JsonNode result = JSON.readTree(dto.query().query());
        assertThat(result.at("/query/authorizationFilters").isArray()).isTrue();
        assertThat(result.at("/query/authorizationFilters")).isEmpty();
        assertCarriesNoConsentValue(result);
        assertThat(JSON.writeValueAsString(dto)).doesNotContain(STUDY_CONSENT, owned.getUserUuid().toString());
    }

    @Test
    void keepsSampleInTheReturnedQuery() throws Exception {
        ObjectNode query = v3QueryWithServerBuiltFilters(filter(SEX, "Male"));
        query.put("sample", true);

        JsonNode result = mapped(enveloped(query), "3");

        assertThat(result.at("/query/sample").isBoolean()).isTrue();
        assertThat(result.at("/query/sample").asBoolean()).isTrue();
        assertThat(result.at("/query/expectedResultType").asText()).isEqualTo("DATAFRAME");
    }

    @Test
    void removesAConsentFilterNestedInTheClauseAndKeepsTheFilterBesideIt() throws Exception {
        JsonNode clause = group(
            "AND", filter(SEX, "Male"), group("OR", group("AND", filter(CONSENTS, STUDY_CONSENT), filter(AGE, "40"))),
            filter(TOPMED_CONSENTS, TOPMED_CONSENT), group("AND", filter(HARMONIZED_CONSENT, HARMONIZED_STUDY_CONSENT))
        );

        JsonNode result = mapped(enveloped(v3QueryWithServerBuiltFilters(clause)), "3");

        assertThat(result.at("/query/phenotypicClause"))
            .isEqualTo(group("AND", filter(SEX, "Male"), group("OR", group("AND", filter(AGE, "40")))));
        assertThat(result.toString()).doesNotContain("_consents", "_harmonized_consent");
        assertCarriesNoConsentValue(result);
    }

    @Test
    void returnsANullClauseWhenTheStoredClauseHeldOnlyConsentFilters() throws Exception {
        JsonNode clause = group("AND", filter(CONSENTS, STUDY_CONSENT), group("OR", filter(TOPMED_CONSENTS, TOPMED_CONSENT)));

        JsonNode result = mapped(enveloped(v3QueryWithServerBuiltFilters(clause)), "3");

        assertThat(result.path("query").has("phenotypicClause")).isTrue();
        assertThat(result.at("/query/phenotypicClause").isNull()).isTrue();
        assertThat(result.at("/query/select/0").asText()).isEqualTo(AGE);
        assertCarriesNoConsentValue(result);
    }

    @Test
    void keepsAFilterOnParentConsents() throws Exception {
        JsonNode clause = group("AND", filter(PARENT_CONSENTS, "phs000004.c1"), filter(CONSENTS, STUDY_CONSENT));

        JsonNode result = mapped(enveloped(v3QueryWithServerBuiltFilters(clause)), "3");

        assertThat(result.at("/query/phenotypicClause")).isEqualTo(group("AND", filter(PARENT_CONSENTS, "phs000004.c1")));
    }

    @Test
    void keepsAConsentPathInSelect() throws Exception {
        ObjectNode query = v3QueryWithServerBuiltFilters(filter(SEX, "Male"));
        ((ArrayNode) query.get("select")).add(CONSENTS);

        JsonNode result = mapped(enveloped(query), "3");

        assertThat(result.at("/query/select")).isEqualTo(JSON.valueToTree(List.of(AGE, CONSENTS)));
    }

    @Test
    void translatesARowStoredBeforeV3AndReturnsItWithoutItsConsentFilters() throws Exception {
        ObjectNode legacy = JSON.createObjectNode();
        ObjectNode categoryFilters = legacy.putObject("categoryFilters");
        categoryFilters.putArray(CONSENTS).add(STUDY_CONSENT);
        categoryFilters.putArray(HARMONIZED_CONSENT).add(HARMONIZED_STUDY_CONSENT);
        categoryFilters.putArray(TOPMED_CONSENTS).add(TOPMED_CONSENT);
        categoryFilters.putArray(SEX).add("Female");
        legacy.putArray("fields").add(AGE).add(CONSENTS);
        legacy.put("expectedResultType", "DATAFRAME");

        JsonNode result = mapped(enveloped(legacy), "2");

        assertThat(result.path("query").has("categoryFilters")).isFalse();
        assertThat(result.at("/query/select")).isEqualTo(JSON.valueToTree(List.of(AGE, CONSENTS)));
        assertThat(result.at("/query/expectedResultType").asText()).isEqualTo("DATAFRAME");
        assertThat(result.at("/query/phenotypicClause").findValuesAsText("conceptPath")).containsExactly(SEX);
        assertThat(result.at("/query/phenotypicClause").findValues("values")).containsExactly(JSON.valueToTree(List.of("Female")));
        assertCarriesNoConsentValue(result);
    }

    @Test
    void returnsADoubleEncodedStoredQueryWithoutItsConsentValues() throws Exception {
        ObjectNode query = v3QueryWithServerBuiltFilters(group("AND", filter(SEX, "Male"), filter(CONSENTS, STUDY_CONSENT)));
        String stored = JSON.createObjectNode().put("query", query.toString()).toString();

        JsonNode result = mapped(stored, "3");

        assertThat(result.path("query").isObject()).isTrue();
        assertThat(result.at("/query/authorizationFilters")).isEmpty();
        assertThat(result.at("/query/phenotypicClause")).isEqualTo(group("AND", filter(SEX, "Male")));
        assertCarriesNoConsentValue(result);
    }

    @Test
    void keepsTheMembersStoredBesideTheQuery() throws Exception {
        ObjectNode stored = JSON.createObjectNode();
        stored.set("query", v3QueryWithServerBuiltFilters(filter(CONSENTS, STUDY_CONSENT)));
        stored.put("commonAreaUUID", "common-id");
        stored.put("resourceUUID", "resource-id");
        stored.put("institutionOfOrigin", "site-a");

        JsonNode result = mapped(stored.toString(), "3");

        assertThat(result.fieldNames()).toIterable().containsExactly("query", "commonAreaUUID", "resourceUUID", "institutionOfOrigin");
        assertThat(result.path("commonAreaUUID").asText()).isEqualTo("common-id");
        assertThat(result.path("resourceUUID").asText()).isEqualTo("resource-id");
        assertThat(result.path("institutionOfOrigin").asText()).isEqualTo("site-a");
        assertCarriesNoConsentValue(result);
    }

    @Test
    void returnsAQueryThatHoldsNoConsentValueAsItDidBefore() throws Exception {
        ObjectNode query = v3QueryWithServerBuiltFilters(group("AND", filter(SEX, "Male"), filter(AGE, "40")));
        ((ArrayNode) query.get("authorizationFilters")).removeAll();

        assertThat(mapped(enveloped(query), "3").get("query")).isEqualTo(query);
    }

    @Test
    void readsTheVariantOfAStoredQueryFromItsBody() {
        assertThat(
            mapper.variantOf(new Query().setQuery("{\"query\":{\"expectedResultType\":\"DATAFRAME_PFB\",\"sample\":true}}").setVersion("3"))
        ).isEqualTo(new NamedDatasetMapper.QueryVariant(true, "DATAFRAME_PFB"));
        assertThat(mapper.variantOf(new Query().setQuery("{\"query\":{\"expectedResultType\":\"DATAFRAME\"}}").setVersion("3")))
            .isEqualTo(new NamedDatasetMapper.QueryVariant(false, "DATAFRAME"));
        assertThat(mapper.variantOf(new Query().setQuery(LEGACY).setVersion("2")))
            .isEqualTo(new NamedDatasetMapper.QueryVariant(false, "DATAFRAME"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not json", "[]", "{\"query\":null}", "{\"query\":{\"select\":{}}}", "{\"query\":{\"select\":[]}}"})
    void readsNoResultTypeFromABodyThatNamesNone(String stored) {
        assertThat(mapper.variantOf(new Query().setQuery(stored).setVersion("3")))
            .isEqualTo(new NamedDatasetMapper.QueryVariant(false, null));
    }

    @Test
    void toDtoCarriesTheLinkedQueries() {
        UUID full = UUID.randomUUID();
        UUID sample = UUID.randomUUID();
        NamedDataset entity = new NamedDataset().setUser("alice@example.com").setName("d1");
        entity.setUuid(UUID.randomUUID());

        NamedDatasetDto dto =
            mapper.toDto(entity, List.of(new LinkedQueryDto(full, false, "DATAFRAME"), new LinkedQueryDto(sample, true, "DATAFRAME")));

        assertThat(dto.queries())
            .containsExactly(new LinkedQueryDto(full, false, "DATAFRAME"), new LinkedQueryDto(sample, true, "DATAFRAME"));
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
