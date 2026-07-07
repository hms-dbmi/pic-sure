package edu.harvard.dbmi.avillach.dictionary.concept.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;


class ConceptTypeTest {

    @Test
    void shouldGetValueOf() {
        ConceptType actual = ConceptType.valueOf(StringUtils.capitalize("categorical"));
        ConceptType expected = ConceptType.Categorical;

        Assertions.assertEquals(expected, actual);
    }
}
