package edu.harvard.dbmi.avillach.dictionary.concept;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConceptResultSetUtilTest {

    @Test
    void shouldParseValues() {
        List<String> actual = new ConceptResultSetUtil().parseValues("[\"Look, I'm valid json\"]");
        List<String> expected = List.of("Look, I'm valid json");

        Assertions.assertEquals(expected, actual);
    }
}
