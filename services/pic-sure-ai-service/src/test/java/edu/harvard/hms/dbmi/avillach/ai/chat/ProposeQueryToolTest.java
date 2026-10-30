package edu.harvard.hms.dbmi.avillach.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProposeQueryTool} binds and validates a proposal's arguments for real -- the same {@code Query} shape {@code pic-sure-mcp}'s own
 * tools use -- without ever promoting the result anywhere (see the class Javadoc for why).
 */
class ProposeQueryToolTest {

    private final ProposeQueryTool tool = new ProposeQueryTool(new ObjectMapper());

    @Test
    void acceptsAProposalWithAllThreeFieldsPresent() {
        ToolResult result = tool.propose("""
            {"query":{"phenotypicClause":{"operator":"AND","phenotypicClauses":[\
            {"phenotypicFilterType":"FILTER","conceptPath":"\\\\demo\\\\sex\\\\","values":["Female"]}]}},\
            "facets":[{"category":"study","name":"demo"}],"search":{"text":"blood pressure"}}""");

        assertFalse(result.error(), result.content());
    }

    @Test
    void acceptsAProposalWithNoFieldsPresent() {
        ToolResult result = tool.propose("{}");

        assertFalse(result.error(), result.content());
    }

    @Test
    void acceptsBlankArguments() {
        ToolResult result = tool.propose("");

        assertFalse(result.error(), result.content());
    }

    @Test
    void rejectsAnUnknownTopLevelField() {
        ToolResult result = tool.propose("{\"notAField\":true}");

        assertTrue(result.error());
        assertTrue(result.content().contains("notAField"), result.content());
    }

    @Test
    void rejectsAFacetMissingItsName() {
        ToolResult result = tool.propose("{\"facets\":[{\"category\":\"study\"}]}");

        assertTrue(result.error());
    }

    @Test
    void rejectsAFacetWithABlankCategory() {
        ToolResult result = tool.propose("{\"facets\":[{\"category\":\"\",\"name\":\"demo\"}]}");

        assertTrue(result.error());
    }

    @Test
    void rejectsAQueryThatIsNotAnObject() {
        ToolResult result = tool.propose("{\"query\":\"not an object\"}");

        assertTrue(result.error());
    }

    @Test
    void rejectsASearchThatIsNotAnObject() {
        ToolResult result = tool.propose("{\"search\":[1,2,3]}");

        assertTrue(result.error());
    }

    @Test
    void rejectsAFilterMissingItsConceptPath() {
        ToolResult result = tool.propose("{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\"}}}");

        assertTrue(result.error());
        assertTrue(result.content().contains("conceptPath"), result.content());
    }

    @Test
    void rejectsASubqueryWithNoNestedClauses() {
        ToolResult result = tool.propose("{\"query\":{\"phenotypicClause\":{\"operator\":\"AND\"}}}");

        assertTrue(result.error());
        assertTrue(result.content().contains("phenotypicClauses"), result.content());
    }

    @Test
    void rejectsADisallowedFieldLikeNotOnAFilter() {
        ToolResult result = tool.propose(
            "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\\",\"not\":true}}}"
        );

        assertTrue(result.error());
        assertTrue(result.content().contains("not"), result.content());
    }

    @Test
    void rejectsUnparseableJson() {
        ToolResult result = tool.propose("not json at all");

        assertTrue(result.error());
    }
}
