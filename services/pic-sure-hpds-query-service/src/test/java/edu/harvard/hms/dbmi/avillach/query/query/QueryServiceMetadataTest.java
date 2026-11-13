package edu.harvard.hms.dbmi.avillach.query.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.config.QueryRowLockProperties;
import edu.harvard.hms.dbmi.avillach.query.config.SampleProperties;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Covers what a metadata read returns for each shape a stored query row can have: the query comes back, and no consent value, owner or run
 * comes back with it. Every v3 body is written the way the service stores it, by serializing the model.
 */
@ExtendWith(MockitoExtension.class)
class QueryServiceMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONSENTS = "\\_consents\\";
    private static final String TOPMED_CONSENTS = "\\_topmed_consents\\";
    private static final String HARMONIZED_CONSENT = "\\_harmonized_consent\\";
    private static final String PARENT_CONSENTS = "\\_parent_consents\\";
    private static final String SEX = "\\demographics\\SEX\\";
    private static final String AGE = "\\demographics\\AGE\\";

    private static final String STUDY_CONSENT = "phs000001.c1";
    private static final String TOPMED_CONSENT = "phs000002.c2";
    private static final String HARMONIZED_STUDY_CONSENT = "phs000003.c1";
    private static final String PARENT_CONSENT = "phs000004.c1";

    private static final UUID OWNER = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final UUID RUN = UUID.fromString("7c1b1f0e-2a3b-4c4d-8e5f-0a1b2c3d4e5f");

    private static final List<AuthorizationFilter> SERVER_BUILT_FILTERS = List.of(
        new AuthorizationFilter(CONSENTS, Set.of(STUDY_CONSENT)), new AuthorizationFilter(TOPMED_CONSENTS, Set.of(TOPMED_CONSENT)),
        new AuthorizationFilter(HARMONIZED_CONSENT, Set.of(HARMONIZED_STUDY_CONSENT))
    );

    @Mock
    private OperationsClient operationsClient;

    @Mock
    private ResourceWebClient hpds;

    @Mock
    private ConsentAuthorizationService consentAuthorization;

    private QueryService service;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void buildService() {
        service = new QueryService(
            operationsClient, hpds, new HpdsBackendSelector(new HpdsProperties()), consentAuthorization,
            new SampleRules(new SampleProperties()), new QueryRowLocks(new QueryRowLockProperties()), new UnsettledQueryRows()
        );
    }

    private static PhenotypicFilter valueFilter(String conceptPath, String... values) {
        return new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, Set.of(values), null, null, null);
    }

    private static PhenotypicFilter ageFilter() {
        return new PhenotypicFilter(PhenotypicFilterType.FILTER, AGE, null, 18.0, 65.0, false);
    }

    private static PhenotypicSubquery group(Operator operator, PhenotypicClause... clauses) {
        return new PhenotypicSubquery(null, List.of(clauses), operator);
    }

    private static Query queryWith(List<AuthorizationFilter> authorizationFilters, PhenotypicClause clause) {
        return new Query(List.of(AGE), authorizationFilters, clause, List.of(), ResultType.DATAFRAME, null, null);
    }

    private static String stored(Object query) throws JsonProcessingException {
        return MAPPER.writeValueAsString(new GeneralQueryRequest().setQuery(query));
    }

    private static JsonNode tree(Object value) throws JsonProcessingException {
        return MAPPER.readTree(MAPPER.writeValueAsString(value));
    }

    private StoredQuery ownerlessRow(String body, String version) {
        return new StoredQuery(id, body, "rr-1", "AVAILABLE", version, null);
    }

    private QueryStatus metadataOf(StoredQuery row) {
        when(operationsClient.get(id)).thenReturn(row);
        return service.queryMetadata(id);
    }

    private static JsonNode queryJson(QueryStatus answer) throws JsonProcessingException {
        return tree(answer.getResultMetadata().get("queryJson"));
    }

    private static void assertCarriesNoConsentValue(QueryStatus answer) throws JsonProcessingException {
        assertThat(MAPPER.writeValueAsString(answer)).doesNotContain(STUDY_CONSENT, TOPMED_CONSENT, HARMONIZED_STUDY_CONSENT);
    }

    @Test
    void ownedRowComesBackWithItsQueryAndWithoutItsOwnerConsentsOrRun() throws Exception {
        Query query = queryWith(List.of(), valueFilter(SEX, "Male")).setPicsureId(id);
        StoredQuery row = new StoredQuery(
            id, stored(query), id.toString(), "AVAILABLE", "3", null, 1L, 2L, OWNER, Map.of(CONSENTS, List.of(STUDY_CONSENT)), RUN
        );

        QueryStatus answer = metadataOf(row);

        assertThat(answer.getPicsureResultId()).isEqualTo(id);
        assertThat(queryJson(answer).path("query")).isEqualTo(tree(query));
        assertThat(MAPPER.writeValueAsString(answer)).doesNotContain(OWNER.toString(), RUN.toString(), "userUuid", "runId", CONSENTS);
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void ownerlessRowStoredWithServerBuiltAuthorizationFiltersComesBackWithNone() throws Exception {
        Query query = queryWith(SERVER_BUILT_FILTERS, valueFilter(SEX, "Male"));

        QueryStatus answer = metadataOf(ownerlessRow(stored(query), "3"));

        JsonNode returned = queryJson(answer).path("query");
        assertThat(returned.path("authorizationFilters").isArray()).isTrue();
        assertThat(returned.path("authorizationFilters")).isEmpty();
        assertThat(returned).isEqualTo(tree(query.setAuthorizationFilters(List.of())));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void consentFiltersNestedInThePhenotypicClauseAreRemovedAndTheFiltersBesideThemStay() throws Exception {
        PhenotypicClause storedClause = group(
            Operator.AND, valueFilter(SEX, "Male"),
            group(
                Operator.OR, group(Operator.AND, valueFilter(CONSENTS, STUDY_CONSENT), ageFilter()),
                valueFilter(TOPMED_CONSENTS, TOPMED_CONSENT)
            ), group(Operator.AND, valueFilter(HARMONIZED_CONSENT, HARMONIZED_STUDY_CONSENT))
        );
        PhenotypicClause expectedClause =
            group(Operator.AND, valueFilter(SEX, "Male"), group(Operator.OR, group(Operator.AND, ageFilter())));

        QueryStatus answer = metadataOf(ownerlessRow(stored(queryWith(List.of(), storedClause)), "3"));

        assertThat(queryJson(answer).path("query")).isEqualTo(tree(queryWith(List.of(), expectedClause)));
        assertThat(MAPPER.writeValueAsString(answer)).doesNotContain("_consents", "_harmonized_consent");
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void clauseThatIsOnlyAConsentFilterComesBackNull() throws Exception {
        Query query = queryWith(List.of(), valueFilter(CONSENTS, STUDY_CONSENT));

        QueryStatus answer = metadataOf(ownerlessRow(stored(query), "3"));

        JsonNode returned = queryJson(answer).path("query");
        assertThat(returned.has("phenotypicClause")).isTrue();
        assertThat(returned.get("phenotypicClause").isNull()).isTrue();
        assertThat(returned).isEqualTo(tree(queryWith(List.of(), null)));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void clauseWhoseGroupsHoldOnlyConsentFiltersComesBackNull() throws Exception {
        PhenotypicClause storedClause = group(
            Operator.AND,
            group(Operator.OR, valueFilter(CONSENTS, STUDY_CONSENT), valueFilter(HARMONIZED_CONSENT, HARMONIZED_STUDY_CONSENT)),
            valueFilter(TOPMED_CONSENTS, TOPMED_CONSENT)
        );

        QueryStatus answer = metadataOf(ownerlessRow(stored(queryWith(List.of(), storedClause)), "3"));

        assertThat(queryJson(answer).path("query")).isEqualTo(tree(queryWith(List.of(), null)));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void filterOnParentConsentsStays() throws Exception {
        PhenotypicFilter parentConsents = valueFilter(PARENT_CONSENTS, PARENT_CONSENT);
        PhenotypicClause storedClause = group(Operator.AND, parentConsents, valueFilter(CONSENTS, STUDY_CONSENT));

        QueryStatus answer = metadataOf(ownerlessRow(stored(queryWith(SERVER_BUILT_FILTERS, storedClause)), "3"));

        assertThat(queryJson(answer).path("query")).isEqualTo(tree(queryWith(List.of(), group(Operator.AND, parentConsents))));
        assertThat(MAPPER.writeValueAsString(answer)).contains(PARENT_CONSENT);
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void consentPathInSelectStays() throws Exception {
        List<String> select = List.of(CONSENTS, AGE, TOPMED_CONSENTS, HARMONIZED_CONSENT);
        Query query = new Query(select, SERVER_BUILT_FILTERS, null, List.of(), ResultType.DATAFRAME, null, null);

        QueryStatus answer = metadataOf(ownerlessRow(stored(query), "3"));

        assertThat(queryJson(answer).path("query").path("select")).isEqualTo(tree(select));
        assertThat(queryJson(answer).path("query")).isEqualTo(tree(query.setAuthorizationFilters(List.of())));
        assertCarriesNoConsentValue(answer);
    }

    private static Map<String, Object> preV3QueryWithConsentCategoryFilters() {
        Map<String, Object> categoryFilters = new LinkedHashMap<>();
        categoryFilters.put(CONSENTS, List.of(STUDY_CONSENT));
        categoryFilters.put(SEX, List.of("Male"));
        categoryFilters.put(TOPMED_CONSENTS, List.of(TOPMED_CONSENT));
        categoryFilters.put(HARMONIZED_CONSENT, List.of(HARMONIZED_STUDY_CONSENT));
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("expectedResultType", "DATAFRAME");
        query.put("fields", List.of(CONSENTS, AGE));
        query.put("categoryFilters", categoryFilters);
        Map<String, Object> numericFilters = new LinkedHashMap<>();
        numericFilters.put(AGE, Map.of("min", 18.0, "max", 65.0));
        numericFilters.put(HARMONIZED_CONSENT, Map.of("min", 1.0, "max", 2.0));
        query.put("numericFilters", numericFilters);
        return query;
    }

    @Test
    void preV3RowComesBackTranslatedWithoutItsConsentCategoryFilters() throws Exception {
        PhenotypicFilter sex = new PhenotypicFilter(PhenotypicFilterType.FILTER, SEX, Set.of("Male"), null, null, false);
        PhenotypicClause expectedClause = new PhenotypicSubquery(false, List.of(sex, ageFilter()), Operator.AND);
        Query expected = new Query(List.of(CONSENTS, AGE), List.of(), expectedClause, List.of(), ResultType.DATAFRAME, null, null);

        QueryStatus answer = metadataOf(ownerlessRow(stored(preV3QueryWithConsentCategoryFilters()), null));

        JsonNode returned = queryJson(answer).path("query");
        assertThat(returned.has("categoryFilters")).isFalse();
        assertThat(returned).isEqualTo(tree(expected));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void preV3RowThatCannotBeTranslatedComesBackAsStoredWithoutItsConsentCategoryFilters() throws Exception {
        Map<String, Object> firstGroup = Map.of("categoryVariantInfoFilters", Map.of("Gene_with_variant", List.of("APOE")));
        Map<String, Object> secondGroup = Map.of("categoryVariantInfoFilters", Map.of("Gene_with_variant", List.of("BRCA1")));
        Map<String, Object> storedQuery = preV3QueryWithConsentCategoryFilters();
        storedQuery.put("variantInfoFilters", List.of(firstGroup, secondGroup));
        Map<String, Object> expected = new LinkedHashMap<>(storedQuery);
        expected.put("categoryFilters", Map.of(SEX, List.of("Male")));
        expected.put("numericFilters", Map.of(AGE, Map.of("min", 18.0, "max", 65.0)));

        QueryStatus answer = metadataOf(ownerlessRow(stored(storedQuery), null));

        JsonNode returned = queryJson(answer).path("query");
        assertThat(returned.has("phenotypicClause")).isFalse();
        assertThat(returned).isEqualTo(tree(expected));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void preV3RowWhoseQueryIsStoredAsJsonTextComesBackWithoutItsConsentCategoryFilters() throws Exception {
        Map<String, Object> storedQuery = preV3QueryWithConsentCategoryFilters();
        Map<String, Object> expected = new LinkedHashMap<>(storedQuery);
        expected.put("categoryFilters", Map.of(SEX, List.of("Male")));
        expected.put("numericFilters", Map.of(AGE, Map.of("min", 18.0, "max", 65.0)));
        String body = MAPPER.writeValueAsString(Map.of("query", MAPPER.writeValueAsString(storedQuery)));

        QueryStatus answer = metadataOf(ownerlessRow(body, null));

        JsonNode returned = queryJson(answer).path("query");
        assertThat(returned.isTextual()).isTrue();
        assertThat(MAPPER.readTree(returned.asText())).isEqualTo(tree(expected));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void rowWithoutAVersionThatHoldsAV3QueryComesBackAsTheEmptyQueryItsTranslationGives() throws Exception {
        PhenotypicClause storedClause = group(Operator.AND, valueFilter(SEX, "Male"), valueFilter(CONSENTS, STUDY_CONSENT));
        Query emptyTranslation = new Query(List.of(), List.of(), null, List.of(), ResultType.DATAFRAME, null, null);

        QueryStatus answer = metadataOf(ownerlessRow(stored(queryWith(SERVER_BUILT_FILTERS, storedClause)), null));

        assertThat(queryJson(answer).path("query")).isEqualTo(tree(emptyTranslation));
        assertThat(MAPPER.writeValueAsString(answer)).doesNotContain("demographics", "_consents", "_harmonized_consent");
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void sampleRowKeepsSampleAndEveryOtherMemberOfItsQuery() throws Exception {
        PhenotypicClause clause = group(Operator.OR, valueFilter(SEX, "Male", "Female"), ageFilter());
        List<GenomicFilter> genomicFilters = List.of(
            new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null), new GenomicFilter("Variant_frequency", null, 0.5f, 1.0f)
        );
        Query query = new Query(
            List.of(AGE, SEX), SERVER_BUILT_FILTERS, clause, genomicFilters, ResultType.DATAFRAME_PFB, id, UUID.randomUUID(), true
        );

        QueryStatus answer = metadataOf(ownerlessRow(stored(query), "3"));

        JsonNode returned = queryJson(answer).path("query");
        assertThat(returned.path("sample").isBoolean()).isTrue();
        assertThat(returned.path("sample").asBoolean()).isTrue();
        assertThat(returned).isEqualTo(tree(query.setAuthorizationFilters(List.of())));
        assertThat(queryJson(answer)).isEqualTo(tree(new GeneralQueryRequest().setQuery(query.setAuthorizationFilters(List.of()))));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void queryStoredAsJsonTextComesBackAsJsonTextWithoutItsConsentValues() throws Exception {
        PhenotypicClause storedClause = group(Operator.AND, valueFilter(SEX, "Male"), valueFilter(CONSENTS, STUDY_CONSENT));
        Query query = queryWith(SERVER_BUILT_FILTERS, storedClause);
        String body = MAPPER.writeValueAsString(Map.of("query", MAPPER.writeValueAsString(query)));

        QueryStatus answer = metadataOf(ownerlessRow(body, "3"));

        JsonNode returned = queryJson(answer).path("query");
        assertThat(returned.isTextual()).isTrue();
        assertThat(MAPPER.readTree(returned.asText())).isEqualTo(tree(queryWith(List.of(), group(Operator.AND, valueFilter(SEX, "Male")))));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void queryStoredAsJsonTextThatHoldsNoConsentValueComesBackCharacterForCharacter() throws Exception {
        String queryText = "{ \"expectedResultType\" : \"COUNT\",  \"select\" : [ ] }";
        String body = MAPPER.writeValueAsString(Map.of("query", queryText));

        QueryStatus answer = metadataOf(ownerlessRow(body, "3"));

        assertThat(queryJson(answer).path("query").asText()).isEqualTo(queryText);
    }

    @Test
    void v3QueryStoredWithoutAnEnvelopeComesBackWithoutItsConsentValues() throws Exception {
        PhenotypicClause storedClause = group(Operator.AND, valueFilter(SEX, "Male"), valueFilter(TOPMED_CONSENTS, TOPMED_CONSENT));
        Query query = queryWith(SERVER_BUILT_FILTERS, storedClause);

        QueryStatus answer = metadataOf(ownerlessRow(MAPPER.writeValueAsString(query), "3"));

        assertThat(queryJson(answer)).isEqualTo(tree(queryWith(List.of(), group(Operator.AND, valueFilter(SEX, "Male")))));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void preV3QueryStoredWithoutAnEnvelopeComesBackWithoutItsConsentCategoryFilters() throws Exception {
        Map<String, Object> storedQuery = preV3QueryWithConsentCategoryFilters();
        Map<String, Object> expected = new LinkedHashMap<>(storedQuery);
        expected.put("categoryFilters", Map.of(SEX, List.of("Male")));
        expected.put("numericFilters", Map.of(AGE, Map.of("min", 18.0, "max", 65.0)));

        QueryStatus answer = metadataOf(ownerlessRow(MAPPER.writeValueAsString(storedQuery), null));

        assertThat(queryJson(answer)).isEqualTo(tree(expected));
        assertCarriesNoConsentValue(answer);
    }

    @Test
    void bodyThatHoldsNoConsentValueComesBackAsStored() throws Exception {
        Map<String, Object> emptyGroup = Map.of("operator", "OR", "phenotypicClauses", List.of());
        Map<String, Object> sexFilter = Map.of("phenotypicFilterType", "FILTER", "conceptPath", SEX, "values", List.of("Male"));
        Map<String, Object> clause = Map.of("operator", "AND", "phenotypicClauses", List.of(sexFilter, emptyGroup));
        Map<String, Object> query = Map.of("select", List.of(AGE), "phenotypicClause", clause, "expectedResultType", "COUNT");
        Map<String, Object> body = Map.of("resourceUUID", UUID.randomUUID().toString(), "query", query);

        QueryStatus answer = metadataOf(ownerlessRow(MAPPER.writeValueAsString(body), "3"));

        assertThat(queryJson(answer)).isEqualTo(tree(body));
    }

    @Test
    void rowWhoseBodyIsNotJsonComesBackWithoutAQuery() {
        assertThat(metadataOf(ownerlessRow("", "3")).getResultMetadata()).doesNotContainKey("queryJson");
        assertThat(metadataOf(ownerlessRow("{\"query\":", null)).getResultMetadata()).doesNotContainKey("queryJson");
    }

    @Test
    void metadataAsksNeitherTheConsentServiceNorHpds() throws Exception {
        metadataOf(ownerlessRow(stored(queryWith(SERVER_BUILT_FILTERS, null)), "3"));

        verifyNoInteractions(consentAuthorization, hpds);
    }
}
