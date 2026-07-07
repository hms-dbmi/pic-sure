package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


class VcfIndexFileParserTest {


    private VcfIndexFileParser vcfIndexFileParser;

    @BeforeEach
    public void setup() {
        vcfIndexFileParser = new VcfIndexFileParser();
    }

    @Test
    public void parse_validFile() throws IOException {
        Resource file = new ClassPathResource("vcf-index/valid.tsv");
        List<VCFIndexLine> vcfIndexLines = vcfIndexFileParser.parse(file.getFile());
        assertEquals(23, vcfIndexLines.size());
        VCFIndexLine line1 = vcfIndexLines.get(0);
        // verify we are trimming whitespace
        for (String sampleId : line1.getSampleIds()) {
            assertFalse(sampleId.contains(" "));
        }
    }

    @Test
    public void parse_validFileNoQuotes() throws IOException {
        Resource file = new ClassPathResource("vcf-index/valid-no-quotes.tsv");
        List<VCFIndexLine> vcfIndexLines = vcfIndexFileParser.parse(file.getFile());
        assertEquals(23, vcfIndexLines.size());
    }

    @Test
    public void parse_invalidFileRow2Col3() {
        Resource file = new ClassPathResource("vcf-index/invalid-row2-col3.tsv");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> vcfIndexFileParser.parse(file.getFile()));
        assertTrue(exception.getMessage().contains("line 2"));
        assertTrue(exception.getMessage().contains("column 3"));
        assertTrue(exception.getMessage().contains("Value = \"x\""));
    }

    @Test
    public void parse_invalidFileRow6Col4() {
        Resource file = new ClassPathResource("vcf-index/invalid-row6-col4.tsv");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> vcfIndexFileParser.parse(file.getFile()));
        assertTrue(exception.getMessage().contains("line 6"));
        assertTrue(exception.getMessage().contains("column 4"));
        assertTrue(exception.getMessage().contains("Value = \"\""));
    }
}
