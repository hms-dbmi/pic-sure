package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class SummaryColumnMetaTest {

    @Test
    public void merge_bothValidData_defaultFirstValue() {
        SummaryColumnMeta entity1 = new SummaryColumnMeta();
        SummaryColumnMeta entity2 = new SummaryColumnMeta();

        entity1.setName("name1").setWidthInBytes(4).setCategorical(true).setCategoryValues(Set.of("James", "Jean-Luc")).setMin(10.0)
            .setMax(40.0).setPatientCount(300).setHasTimestamp(false);

        entity2.setName("name2").setWidthInBytes(8).setCategorical(false).setCategoryValues(Set.of("Benjamin", "Kathryn")).setMin(20.0)
            .setMax(50.0).setPatientCount(300).setHasTimestamp(false);

        SummaryColumnMeta merged = entity1.merge(entity2);
        assertEquals("name1", merged.getName());
        assertEquals(8, merged.getWidthInBytes());
        assertTrue(merged.isCategorical());
        assertEquals(Set.of("James", "Jean-Luc", "Benjamin", "Kathryn"), merged.getCategoryValues());
        assertEquals(10, merged.getMin());
        assertEquals(50, merged.getMax());
        assertEquals(600, merged.getPatientCount());
        assertFalse(merged.hasTimestamp());

        merged = entity2.merge(entity1);
        assertEquals("name2", merged.getName());
        assertEquals(8, merged.getWidthInBytes());
        assertFalse(merged.isCategorical());
        assertEquals(Set.of("James", "Jean-Luc", "Benjamin", "Kathryn"), merged.getCategoryValues());
        assertEquals(10, merged.getMin());
        assertEquals(50, merged.getMax());
        assertEquals(600, merged.getPatientCount());
        assertFalse(merged.hasTimestamp());
    }

    @Test
    public void merge_emptyValues() {
        SummaryColumnMeta entity1 = new SummaryColumnMeta();
        SummaryColumnMeta entity2 = new SummaryColumnMeta();

        SummaryColumnMeta merged = entity1.merge(entity2);
        assertNull(merged.getName());
        assertEquals(0, merged.getWidthInBytes());
        assertFalse(merged.isCategorical());
        assertEquals(Set.of(), merged.getCategoryValues());
        assertNull(merged.getMin());
        assertNull(merged.getMax());
        assertEquals(0, merged.getPatientCount());
        assertFalse(merged.hasTimestamp());
        assertNull(merged.getTimestampMax());
        assertNull(merged.getTimestampMin());

        merged = entity2.merge(entity1);
        assertNull(merged.getName());
        assertEquals(0, merged.getWidthInBytes());
        assertFalse(merged.isCategorical());
        assertEquals(Set.of(), merged.getCategoryValues());
        assertNull(merged.getMin());
        assertNull(merged.getMax());
        assertEquals(0, merged.getPatientCount());
        assertFalse(merged.hasTimestamp());
        assertNull(merged.getTimestampMax());
        assertNull(merged.getTimestampMin());
    }


    @Test
    public void merge_oneNullMinMax_useOtherValue() {
        SummaryColumnMeta entity1 = new SummaryColumnMeta();
        SummaryColumnMeta entity2 = new SummaryColumnMeta();

        entity1.setName("name1").setWidthInBytes(4).setCategorical(true).setCategoryValues(Set.of("James", "Jean-Luc")).setMin(null)
            .setMax(null).setPatientCount(300).setHasTimestamp(true);

        entity2.setName("name2").setWidthInBytes(8).setCategorical(false).setCategoryValues(Set.of("Benjamin", "Kathryn")).setMin(20.0)
            .setMax(50.0).setPatientCount(300).setHasTimestamp(true).setTimestampMax(100L).setTimestampMin(1000L);

        SummaryColumnMeta merged = entity1.merge(entity2);
        assertEquals(20.0, merged.getMin());
        assertEquals(50.0, merged.getMax());
        assertEquals(100L, merged.getTimestampMax());
        assertEquals(1000L, merged.getTimestampMin());

        merged = entity2.merge(entity1);
        assertEquals(20.0, merged.getMin());
        assertEquals(50.0, merged.getMax());
        assertEquals(100L, merged.getTimestampMax());
        assertEquals(1000L, merged.getTimestampMin());
    }

    @Test
    public void merge_oneNullCategoryValues_setOther() {
        SummaryColumnMeta entity1 = new SummaryColumnMeta();
        SummaryColumnMeta entity2 = new SummaryColumnMeta();

        entity1.setCategoryValues(Set.of("Blue", "Green", "Red"));
        SummaryColumnMeta merged = entity1.merge(entity2);
        assertEquals(Set.of("Blue", "Green", "Red"), merged.getCategoryValues());

        merged = entity2.merge(entity1);
        assertEquals(Set.of("Blue", "Green", "Red"), merged.getCategoryValues());
    }
}
