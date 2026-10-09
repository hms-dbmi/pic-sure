package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProposeQueryTool} binds and validates a proposal's arguments for real -- the same {@code Query} shape {@code pic-sure-mcp}'s own
 * tools use -- without ever promoting the result anywhere (see the class Javadoc for why).
 */
class ProposeQueryToolTest {

    private final ProposeQueryTool tool = new ProposeQueryTool(new ObjectMapper());
    private final KnownFacets knownFacets = new KnownFacets();

    ProposeQueryToolTest() {
        knownFacets.learnFrom(List.of(new FacetSelection("study", "demo")));
    }

    private ToolResult propose(String argumentsJson) {
        return tool.propose(argumentsJson, knownFacets).result();
    }

    @Test
    void anAcceptedProposalCarriesTheValidatedParts() {
        ProposeQueryTool.Outcome outcome = tool.propose("""
            {"facets":[{"category":"study","name":"demo"}],"search":"bmi"}""", knownFacets);

        assertFalse(outcome.result().error(), outcome.result().content());
        assertEquals(List.of(new FacetSelection("study", "demo")), outcome.proposal().facets());
        assertEquals("bmi", outcome.proposal().search());
        assertNull(outcome.proposal().query());
    }

    @Test
    void aFacetNoListFacetsResultShowedIsRefusedAndNotAccepted() {
        ProposeQueryTool.Outcome outcome = tool.propose("{\"facets\":[{\"category\":\"study\",\"name\":\"invented\"}]}", knownFacets);

        assertTrue(outcome.result().error());
        assertTrue(outcome.result().content().contains("invented"), outcome.result().content());
        assertTrue(outcome.result().content().contains("list_facets"), outcome.result().content());
        assertNull(outcome.proposal(), "a refused proposal must never be held for the response");
    }

    @Test
    void querySentAtTheTopLevelIsReadAsTheQuery() {
        // Round 17, T10 R1: gpt-oss sent {"select":[...]} with no query wrapper and was refused.
        ProposeQueryTool.Outcome outcome = tool.propose("{\"select\":[\"\\\\demo\\\\race\\\\\"]}", knownFacets);

        assertFalse(outcome.result().error(), outcome.result().content());
        assertEquals(List.of("\\demo\\race\\"), outcome.proposal().query().select());
    }

    @Test
    void anEmptyFacetListIsAValidClearingProposal() {
        ProposeQueryTool.Outcome outcome = tool.propose("{\"facets\":[]}", knownFacets);

        assertFalse(outcome.result().error(), outcome.result().content());
        assertEquals(List.of(), outcome.proposal().facets());
    }

    @Test
    void emptyObjectsSentForFieldsTheModelMeansToOmitAreReadAsNotSent() {
        // The shape gpt-oss sent for a select-only request: no filter and no search, each as {}.
        ProposeQueryTool.Outcome outcome = tool.propose("""
            {"search":{},"query":{"select":["\\\\demo\\\\bmi\\\\"],"genomicFilters":[],"phenotypicClause":{}},"facets":[]}""", knownFacets);

        assertFalse(outcome.result().error(), outcome.result().content());
        assertEquals(List.of("\\demo\\bmi\\"), outcome.proposal().query().select());
        assertNull(outcome.proposal().query().phenotypicClause());
        assertNull(outcome.proposal().search());
    }

    @Test
    void aNonEmptyUnrecognisedClauseStillFailsAndTellsTheModelToOmitItWhenThereIsNothingToFilter() {
        ProposeQueryTool.Outcome outcome = tool.propose("{\"query\":{\"phenotypicClause\":{\"bogus\":1}}}", knownFacets);

        assertTrue(outcome.result().error());
        assertTrue(outcome.result().content().contains("leave phenotypicClause out"), outcome.result().content());
    }

    @Test
    void rejectsAFilterWithBothValuesAndARange() {
        ToolResult result = propose("""
            {"query":{"phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\demo\\\\age\\\\",\
            "values":["a"],"min":1,"max":2}}}""");

        assertTrue(result.error());
        assertTrue(result.content().contains("values"), result.content());
    }

    @Test
    void rejectsARangeWhoseMinExceedsItsMax() {
        ToolResult result = propose("""
            {"query":{"phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\demo\\\\age\\\\",\
            "min":9,"max":2}}}""");

        assertTrue(result.error());
        assertTrue(result.content().contains("min"), result.content());
    }

    @Test
    void rejectsABlankSelectEntry() {
        ToolResult result = propose("{\"query\":{\"select\":[\" \"]}}");

        assertTrue(result.error());
        assertTrue(result.content().contains("select"), result.content());
    }

    @Test
    void acceptsAProposalWithAllThreeFieldsPresent() {
        ToolResult result = propose("""
            {"query":{"phenotypicClause":{"operator":"AND","phenotypicClauses":[\
            {"phenotypicFilterType":"FILTER","conceptPath":"\\\\demo\\\\sex\\\\","values":["Female"]}]}},\
            "facets":[{"category":"study","name":"demo"}],"search":"blood pressure"}""");

        assertFalse(result.error(), result.content());
    }

    @Test
    void rejectsAProposalWithNoFieldsPresent() {
        ToolResult result = propose("{}");

        assertTrue(result.error());
        assertTrue(result.content().contains("empty"), result.content());
    }

    @Test
    void rejectsBlankArguments() {
        ToolResult result = propose("");

        assertTrue(result.error());
        assertTrue(result.content().contains("empty"), result.content());
    }

    @Test
    void rejectsAnUnknownTopLevelField() {
        ToolResult result = propose("{\"notAField\":true}");

        assertTrue(result.error());
        assertTrue(result.content().contains("notAField"), result.content());
    }

    @Test
    void rejectsAFacetMissingItsName() {
        ToolResult result = propose("{\"facets\":[{\"category\":\"study\"}]}");

        assertTrue(result.error());
    }

    @Test
    void rejectsAFacetWithABlankCategory() {
        ToolResult result = propose("{\"facets\":[{\"category\":\"\",\"name\":\"demo\"}]}");

        assertTrue(result.error());
    }

    @Test
    void rejectsAQueryThatIsNotAnObject() {
        ToolResult result = propose("{\"query\":\"not an object\"}");

        assertTrue(result.error());
    }

    @Test
    void rejectsASearchThatIsNotAString() {
        ToolResult result = propose("{\"search\":[1,2,3]}");

        assertTrue(result.error());
    }

    @Test
    void rejectsAFilterMissingItsConceptPath() {
        ToolResult result = propose("{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\"}}}");

        assertTrue(result.error());
        assertTrue(result.content().contains("conceptPath"), result.content());
    }

    @Test
    void rejectsASubqueryWithNoNestedClauses() {
        ToolResult result = propose("{\"query\":{\"phenotypicClause\":{\"operator\":\"AND\"}}}");

        assertTrue(result.error());
        assertTrue(result.content().contains("phenotypicClauses"), result.content());
    }

    @Test
    void rejectsADisallowedFieldLikeNotOnAFilter() {
        ToolResult result = propose(
            "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\\",\"not\":true}}}"
        );

        assertTrue(result.error());
        assertTrue(result.content().contains("not"), result.content());
    }

    @Test
    void rejectsUnparseableJson() {
        ToolResult result = propose("not json at all");

        assertTrue(result.error());
    }
}
