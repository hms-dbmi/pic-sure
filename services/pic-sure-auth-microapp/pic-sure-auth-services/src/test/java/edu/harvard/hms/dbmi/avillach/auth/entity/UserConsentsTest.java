package edu.harvard.hms.dbmi.avillach.auth.entity;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserConsentsTest {

    private final UserConsents.ConsentsJsonConverter converter = new UserConsents.ConsentsJsonConverter();

    @Test
    void readsFlatSet() {
        assertEquals(Set.of("phs000001.c1", "phs000002.c2"), converter.convertToEntityAttribute("[\"phs000001.c1\",\"phs000002.c2\"]"));
    }

    @Test
    void readsLegacyConsentGroups() {
        String legacy = """
            {"\\\\_consents\\\\":["phs000001.c1","phs000002.c2"],
             "\\\\_topmed_consents\\\\":["phs000001.c1"],
             "\\\\_harmonized_consent\\\\":["phs000002.c2"]}""";
        assertEquals(Set.of("phs000001.c1", "phs000002.c2"), converter.convertToEntityAttribute(legacy));
    }

    @Test
    void readsLegacyRowWithoutConsentsGroupAsEmpty() {
        assertEquals(Set.of(), converter.convertToEntityAttribute("{\"\\\\_topmed_consents\\\\\":[\"phs000001.c1\"]}"));
        assertEquals(Set.of(), converter.convertToEntityAttribute("{}"));
    }

    @Test
    void readsUnparseableValuesAsEmpty() {
        assertEquals(Set.of(), converter.convertToEntityAttribute(null));
        assertEquals(Set.of(), converter.convertToEntityAttribute(""));
        assertEquals(Set.of(), converter.convertToEntityAttribute("not json"));
        assertEquals(Set.of(), converter.convertToEntityAttribute("null"));
        assertEquals(Set.of(), converter.convertToEntityAttribute("\"phs000001.c1\""));
        assertEquals(Set.of(), converter.convertToEntityAttribute("[{\"a\":1}]"));
        assertEquals(Set.of(), converter.convertToEntityAttribute("{\"\\\\_consents\\\\\":\"phs000001.c1\"}"));
    }

    @Test
    void writesFlatSet() {
        Set<String> consents = Set.of("phs000001.c1");
        assertEquals("[\"phs000001.c1\"]", converter.convertToDatabaseColumn(consents));
        assertEquals(consents, converter.convertToEntityAttribute(converter.convertToDatabaseColumn(consents)));
    }
}
