package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.util.JsonBlobParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ConceptResultSetUtilTest {

    @Test
    void shouldParseValues() {
        List<String> actual = new JsonBlobParser().parseValues("[\"Look, I'm valid json\"]");
        List<String> expected = List.of("Look, I'm valid json");

        Assertions.assertEquals(expected, actual);
    }
}
