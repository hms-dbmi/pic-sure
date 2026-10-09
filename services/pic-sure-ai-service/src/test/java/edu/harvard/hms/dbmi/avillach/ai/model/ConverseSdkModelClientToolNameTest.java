package edu.harvard.hms.dbmi.avillach.ai.model;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConverseSdkModelClientToolNameTest {

    private static final Set<String> KNOWN = Set.of("search_concepts", "get_concept", "propose_query");

    @Test
    void aKnownNameIsUnchanged() {
        assertEquals("search_concepts", ConverseSdkModelClient.toolName("search_concepts", KNOWN));
    }

    @Test
    void leakedTemplateTokensAfterAKnownNameAreStripped() {
        assertEquals("search_concepts", ConverseSdkModelClient.toolName("search_concepts<|channel|>commentary", KNOWN));
    }

    @Test
    void aMangledNameThatIsClearlyOneKnownToolSnapsToIt() {
        assertEquals("search_concepts", ConverseSdkModelClient.toolName("search_con?commentary??<|end|><|start|>assistant", KNOWN));
        assertEquals("search_concepts", ConverseSdkModelClient.toolName("search_concret", KNOWN));
        assertEquals("search_concepts", ConverseSdkModelClient.toolName("search_conle", KNOWN));
        assertEquals("search_concepts", ConverseSdkModelClient.toolName("search_conconcepts", KNOWN));
        assertEquals("search_concepts", ConverseSdkModelClient.toolName("search_concre", KNOWN));
    }

    @Test
    void aNameCloseToNothingOrToTwoToolsEquallyIsOnlyCleanedSoTheLoopRefusesIt() {
        assertEquals("frobnicate", ConverseSdkModelClient.toolName("frobnicate<|x|>", KNOWN));
        assertEquals("abcdeh", ConverseSdkModelClient.toolName("abcdeh", Set.of("abcdef", "abcdeg")));
        assertTrue(ConverseSdkModelClient.toolName("frobnicate<|x|>", KNOWN).matches("[a-zA-Z0-9_-]+"));
    }

    @Test
    void aNameWithNothingUsableBecomesAPlaceholder() {
        assertEquals(ConverseSdkModelClient.UNKNOWN_TOOL, ConverseSdkModelClient.toolName("<|channel|>", KNOWN));
        assertEquals(ConverseSdkModelClient.UNKNOWN_TOOL, ConverseSdkModelClient.toolName(null, KNOWN));
    }
}
