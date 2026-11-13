package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalRulesAsksToSelectTest {

    @Test
    void messagesThatAskForVariablesInTheExportAreRecognized() {
        for (
            String message : new String[] {"select BMI and age", "Export everyone's BMI", "please add the BMI variable",
                "include age and sex columns in my data", "add BMI to my selection", "I'd like to pick a few variables"}
        ) {
            assertTrue(ProposalRules.asksToSelect(message), message);
        }
    }

    @Test
    void messagesThatAddFiltersOrLookUpVariablesAreNot() {
        for (
            String message : new String[] {"add a filter for age over 50", "include only women", "show me BMI",
                "find variables about smoking", "add diabetes as a filter. what variables exist?"}
        ) {
            assertFalse(ProposalRules.asksToSelect(message), message);
        }
        assertFalse(ProposalRules.asksToSelect(null));
    }
}
