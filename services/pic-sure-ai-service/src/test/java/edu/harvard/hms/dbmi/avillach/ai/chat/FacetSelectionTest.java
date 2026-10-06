package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FacetSelectionTest {

    @Test
    void carriesCategoryAndName() {
        FacetSelection facet = new FacetSelection("study", "phs000001");

        assertThat(facet.category()).isEqualTo("study");
        assertThat(facet.name()).isEqualTo("phs000001");
        assertThat(facet).isEqualTo(new FacetSelection("study", "phs000001"));
    }
}
