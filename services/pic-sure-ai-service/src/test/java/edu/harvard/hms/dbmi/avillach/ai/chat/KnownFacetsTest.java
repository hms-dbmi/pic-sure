package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link KnownFacets} tracks which facets a turn has seen, so a facet the model invented can be refused. */
class KnownFacetsTest {

    private static final String LIST_FACETS_RESULT = """
        {"query":"bmi","categories":[{"name":"study","display":"Study","facets":[{"name":"demo","count":3},{"name":"nhanes","count":9}]},\
        {"name":"sex","facets":[{"name":"female"}]}]}""";

    @Test
    void learnsEveryFacetOfEveryCategoryInAListFacetsResult() {
        KnownFacets known = new KnownFacets();

        known.learnFromListFacets(LIST_FACETS_RESULT);

        assertTrue(known.isKnown(new FacetSelection("study", "demo")));
        assertTrue(known.isKnown(new FacetSelection("study", "nhanes")));
        assertTrue(known.isKnown(new FacetSelection("sex", "female")));
    }

    @Test
    void aFacetNameIsOnlyKnownUnderItsOwnCategory() {
        KnownFacets known = new KnownFacets();
        known.learnFromListFacets(LIST_FACETS_RESULT);

        assertFalse(known.isKnown(new FacetSelection("sex", "demo")));
        assertFalse(known.isKnown(new FacetSelection("study", "invented")));
    }

    @Test
    void theResearchersCurrentSelectionCountsAsKnown() {
        KnownFacets known = new KnownFacets();

        known.learnFrom(List.of(new FacetSelection("study", "demo")));
        known.learnFrom((List<FacetSelection>) null);

        assertTrue(known.isKnown(new FacetSelection("study", "demo")));
    }

    @Test
    void ignoresContentThatIsNotAListFacetsResult() {
        KnownFacets known = new KnownFacets();

        known.learnFromListFacets("not json");
        known.learnFromListFacets("{\"categories\":[{\"facets\":[{\"name\":\"orphan\"}]},{\"name\":\"study\",\"facets\":[{}]}]}");
        known.learnFromListFacets(null);

        assertFalse(known.isKnown(new FacetSelection("study", "orphan")));
    }

    @Test
    void findsTheFirstUnknownFacetInASelection() {
        KnownFacets known = new KnownFacets();
        known.learnFromListFacets(LIST_FACETS_RESULT);

        FacetSelection unknown = known.firstUnknownIn(List.of(new FacetSelection("study", "demo"), new FacetSelection("study", "nope")));

        assertEquals(new FacetSelection("study", "nope"), unknown);
        assertNull(known.firstUnknownIn(List.of(new FacetSelection("study", "demo"))));
    }
}
