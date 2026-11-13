package edu.harvard.hms.dbmi.avillach.hpds.data.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;

/**
 * Pins what {@link StoredQueryConsentStripper} removes from a stored query request body and what it leaves, on bodies built as JSON so that
 * each stored shape is spelled out.
 */
class StoredQueryConsentStripperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CONSENTS = "\\_consents\\";
    private static final String TOPMED_CONSENTS = "\\_topmed_consents\\";
    private static final String HARMONIZED_CONSENT = "\\_harmonized_consent\\";
    private static final String PARENT_CONSENTS = "\\_parent_consents\\";
    private static final String SEX = "\\demographics\\SEX\\";
    private static final String AGE = "\\demographics\\AGE\\";
    private static final String STUDY_CONSENT = "phs000001.c1";

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

    private static ObjectNode v3Query(JsonNode clause) {
        ObjectNode query = JSON.createObjectNode();
        query.putArray("select").add(AGE);
        ObjectNode authorization = query.putArray("authorizationFilters").addObject();
        authorization.put("conceptPath", CONSENTS);
        authorization.putArray("values").add(STUDY_CONSENT);
        query.set("phenotypicClause", clause);
        query.putArray("genomicFilters");
        query.put("expectedResultType", "DATAFRAME");
        return query;
    }

    private static ObjectNode envelope(JsonNode query) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.set("query", query);
        return envelope;
    }

    private static ObjectNode stripped(ObjectNode body) {
        StoredQueryConsentStripper.strip(body);
        return body;
    }

    @Test
    void consentPathsAreTheThreeTheServerWritesAuthorizationFiltersOn() {
        assertThat(AuthorizationFilter.CONSENT_CONCEPT_PATHS).containsExactlyInAnyOrder(CONSENTS, TOPMED_CONSENTS, HARMONIZED_CONSENT);
    }

    @Test
    void authorizationFiltersBecomeAnEmptyList() {
        JsonNode query = stripped(envelope(v3Query(filter(SEX, "Male")))).get("query");

        assertThat(query.get("authorizationFilters").isArray()).isTrue();
        assertThat(query.get("authorizationFilters")).isEmpty();
        assertThat(query.toString()).doesNotContain(STUDY_CONSENT);
    }

    @Test
    void queryStoredWithoutAuthorizationFiltersGainsNoSuchMember() {
        ObjectNode query = v3Query(filter(SEX, "Male"));
        query.remove("authorizationFilters");

        assertThat(stripped(envelope(query)).get("query").has("authorizationFilters")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {CONSENTS, TOPMED_CONSENTS, HARMONIZED_CONSENT})
    void consentFilterNestedInAGroupIsRemovedAndTheFilterBesideItStays(String consentPath) {
        JsonNode clause = group("AND", filter(SEX, "Male"), group("OR", filter(consentPath, STUDY_CONSENT), filter(AGE, "40")));

        JsonNode query = stripped(envelope(v3Query(clause))).get("query");

        assertThat(query.get("phenotypicClause")).isEqualTo(group("AND", filter(SEX, "Male"), group("OR", filter(AGE, "40"))));
    }

    @Test
    void groupLeftWithoutAClauseIsRemovedAndSoIsTheGroupAboveIt() {
        JsonNode clause = group("AND", filter(SEX, "Male"), group("OR", group("AND", filter(CONSENTS, STUDY_CONSENT))));

        JsonNode query = stripped(envelope(v3Query(clause))).get("query");

        assertThat(query.get("phenotypicClause")).isEqualTo(group("AND", filter(SEX, "Male")));
    }

    @Test
    void clauseThatHoldsOnlyConsentFiltersBecomesNull() {
        JsonNode onlyAFilter = stripped(envelope(v3Query(filter(CONSENTS, STUDY_CONSENT)))).get("query");
        JsonNode onlyGroups = stripped(
            envelope(v3Query(group("AND", group("OR", filter(TOPMED_CONSENTS, STUDY_CONSENT)), filter(HARMONIZED_CONSENT, STUDY_CONSENT))))
        ).get("query");

        assertThat(onlyAFilter.has("phenotypicClause")).isTrue();
        assertThat(onlyAFilter.get("phenotypicClause").isNull()).isTrue();
        assertThat(onlyGroups.get("phenotypicClause").isNull()).isTrue();
    }

    @Test
    void groupStoredWithNoClausesIsKept() {
        JsonNode clause = group("AND", filter(SEX, "Male"), group("OR"));

        JsonNode query = stripped(envelope(v3Query(clause))).get("query");

        assertThat(query.get("phenotypicClause")).isEqualTo(group("AND", filter(SEX, "Male"), group("OR")));
    }

    @Test
    void filterOnParentConsentsStays() {
        JsonNode clause = group("AND", filter(PARENT_CONSENTS, STUDY_CONSENT), filter(CONSENTS, "phs000002.c1"));

        JsonNode query = stripped(envelope(v3Query(clause))).get("query");

        assertThat(query.get("phenotypicClause")).isEqualTo(group("AND", filter(PARENT_CONSENTS, STUDY_CONSENT)));
    }

    @Test
    void consentPathInSelectStays() {
        ObjectNode query = v3Query(filter(SEX, "Male"));
        ((ArrayNode) query.get("select")).add(CONSENTS);

        JsonNode select = stripped(envelope(query)).get("query").get("select");

        assertThat(select).hasSize(2);
        assertThat(select.get(1).asText()).isEqualTo(CONSENTS);
    }

    @Test
    void filterMapsOfAQueryStoredBeforeV3LoseTheirConsentEntriesAndItsFieldListsKeepTheirs() {
        ObjectNode query = JSON.createObjectNode();
        ObjectNode categoryFilters = query.putObject("categoryFilters");
        categoryFilters.putArray(CONSENTS).add(STUDY_CONSENT);
        categoryFilters.putArray(HARMONIZED_CONSENT).add(STUDY_CONSENT);
        categoryFilters.putArray(SEX).add("Male");
        ObjectNode numericFilters = query.putObject("numericFilters");
        numericFilters.putObject(TOPMED_CONSENTS).put("min", 1);
        numericFilters.putObject(AGE).put("min", 40);
        query.putArray("fields").add(CONSENTS);
        query.put("expectedResultType", "COUNT");

        JsonNode shown = stripped(envelope(query)).get("query");

        assertThat(shown.get("categoryFilters").fieldNames()).toIterable().containsExactly(SEX);
        assertThat(shown.get("numericFilters").fieldNames()).toIterable().containsExactly(AGE);
        assertThat(shown.get("fields").get(0).asText()).isEqualTo(CONSENTS);
        assertThat(shown.get("expectedResultType").asText()).isEqualTo("COUNT");
    }

    @Test
    void queryStoredAsJsonTextStaysTextAndLosesItsConsentValues() throws JsonProcessingException {
        ObjectNode query = v3Query(group("AND", filter(SEX, "Male"), filter(CONSENTS, STUDY_CONSENT)));
        ObjectNode body = JSON.createObjectNode();
        body.put("query", query.toString());

        StoredQueryConsentStripper.strip(body);

        assertThat(body.get("query").isTextual()).isTrue();
        JsonNode shown = JSON.readTree(body.get("query").asText());
        assertThat(shown.get("authorizationFilters")).isEmpty();
        assertThat(shown.get("phenotypicClause")).isEqualTo(group("AND", filter(SEX, "Male")));
    }

    @Test
    void queryStoredAsJsonTextThatHoldsNoConsentValueIsLeftCharacterForCharacter() {
        String text = "{ \"expectedResultType\" : \"COUNT\",   \"phenotypicClause\" : null }";
        ObjectNode body = JSON.createObjectNode();
        body.put("query", text);

        StoredQueryConsentStripper.strip(body);

        assertThat(body.get("query").asText()).isEqualTo(text);
    }

    @Test
    void bodyWithoutAQueryMemberIsReadAsTheQuery() {
        ObjectNode body = v3Query(group("AND", filter(SEX, "Male"), filter(CONSENTS, STUDY_CONSENT)));

        StoredQueryConsentStripper.strip(body);

        assertThat(body.get("authorizationFilters")).isEmpty();
        assertThat(body.get("phenotypicClause")).isEqualTo(group("AND", filter(SEX, "Male")));
    }

    @Test
    void membersBesideTheQueryStay() {
        ObjectNode body = envelope(v3Query(filter(CONSENTS, STUDY_CONSENT)));
        body.put("commonAreaUUID", "common-id");
        body.put("resourceUUID", "resource-id");

        StoredQueryConsentStripper.strip(body);

        assertThat(body.get("commonAreaUUID").asText()).isEqualTo("common-id");
        assertThat(body.get("resourceUUID").asText()).isEqualTo("resource-id");
        assertThat(body.get("query").get("phenotypicClause").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"[]", "\"text\"", "null", "{\"query\":null}", "{\"query\":[]}", "{\"query\":\"not json\"}", "{\"query\":\"[]\"}"}
    )
    void bodyWithNoReadableQueryIsLeftAsItIs(String stored) throws JsonProcessingException {
        JsonNode body = JSON.readTree(stored);

        StoredQueryConsentStripper.strip(body);

        assertThat(body).isEqualTo(JSON.readTree(stored));
    }

    @Test
    void queryThatHoldsNoConsentValueIsLeftAsItIs() {
        ObjectNode query = v3Query(group("AND", filter(SEX, "Male"), filter(AGE, "40")));
        ((ArrayNode) query.get("authorizationFilters")).removeAll();
        ObjectNode body = envelope(query);
        JsonNode asStored = body.deepCopy();

        StoredQueryConsentStripper.strip(body);

        assertThat(body).isEqualTo(asStored);
    }
}
