package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class PhenoCubeTest {

    @Test
    void shouldGetValuesForKeys() {
        KeyAndValue[] sortedByKey = {
            new KeyAndValue<>(1, "a"),
            new KeyAndValue<>(1, "b"),
            new KeyAndValue<>(2, "c"),
            new KeyAndValue<>(3, "d"),
        };
        PhenoCube<String> subject = new PhenoCube<>("phill the phenocube", String.class);
        subject.setSortedByKey(sortedByKey);

        Set<Integer> patientIds = new LinkedHashSet<>(List.of(3, 2, 1));
        List<KeyAndValue<String>> actual = subject.getValuesForKeys(patientIds);
        List<KeyAndValue<String>> expected = List.of(sortedByKey);

        Assertions.assertEquals(expected, actual);
    }
}