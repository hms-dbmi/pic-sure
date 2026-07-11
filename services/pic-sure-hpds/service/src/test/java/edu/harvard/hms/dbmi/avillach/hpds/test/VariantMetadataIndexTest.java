package edu.harvard.hms.dbmi.avillach.hpds.test;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.util.AssertionErrors.assertEquals;
import static org.springframework.test.util.AssertionErrors.assertNotNull;

public class VariantMetadataIndexTest {

    /**
     * The metadataIndex is non-mutable (or should be) so we only need one object to test
     */
    private static VariantMetadataIndex variantMetadataIndexChr4;
    private static VariantMetadataIndex variantMetadataIndexChr21;
    public static String chr4BinFile = "target/all/chr4/VariantMetadata.javabin";
    public static String chr21BinFile = "target/all/chr21/VariantMetadata.javabin";
    VariantBucketHolder<String[]> bucketCache = new VariantBucketHolder<String[]>();

    // Some known variant specs from the input file. These have been designed for testing partially overlapping specs
    private static final String spec1 = "chr4,9856624,CAAAAA,C,TVP23A,splice_acceptor_variant";
    private static final String spec1Info =
        "Gene_with_variant=TVP23A;Variant_consequence_calculated=splice_acceptor_variant;AC=401;AF=8.00719e-02;NS=2504;AN=5008;EAS_AF=3.37000e-02;EUR_AF=4.97000e-02;AFR_AF=1.64100e-01;AMR_AF=3.75000e-02;SAS_AF=7.57000e-02;DP=18352;AA=G|||;VT=SNP";
    private static final String spec2 = "chr4,9856624,CAAA,C,TVP23A,splice_acceptor_variant";
    private static final String spec2Info =
        "Gene_with_variant=TVP23A;Variant_consequence_calculated=splice_acceptor_variant;AC=62;AF=1.23802e-02;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=1.00000e-03;AFR_AF=4.54000e-02;AMR_AF=1.40000e-03;SAS_AF=0.00000e+00;DP=18328;AA=T|||;VT=SNP";
    private static final String spec3 = "chr4,9856624,CA,C,TVP23A,splice_acceptor_variant";
    private static final String spec3Info =
        "Gene_with_variant=TVP23A;Variant_consequence_calculated=splice_acceptor_variant;AC=8;AF=1.59744e-03;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=0.00000e+00;AFR_AF=6.10000e-03;AMR_AF=0.00000e+00;SAS_AF=0.00000e+00;DP=18519;AA=T|||;VT=SNP";
    private static final String spec4 = "chr4,9856624,C,CA,TVP23A,splice_acceptor_variant";
    private static final String spec4Info =
        "Gene_with_variant=TVP23A;Variant_consequence_calculated=splice_acceptor_variant;AC=75;AF=1.49760e-02;NS=2504;AN=5008;EAS_AF=3.27000e-02;EUR_AF=2.49000e-02;AFR_AF=6.80000e-03;AMR_AF=4.30000e-03;SAS_AF=5.10000e-03;DP=18008;AA=A|||;VT=SNP";
    private static final String spec5 = "chr4,9856624,CAAAAA,CA,TVP23A,splice_acceptor_variant";
    private static final String spec5Info =
        "Gene_with_variant=TVP23A;Variant_consequence_calculated=splice_acceptor_variant;AC=3033;AF=6.05631e-01;NS=2504;AN=5008;EAS_AF=5.23800e-01;EUR_AF=7.54500e-01;AFR_AF=4.28900e-01;AMR_AF=7.82400e-01;SAS_AF=6.50300e-01;DP=20851;VT=INDEL";


    @BeforeAll
    public static void initializeBinfiles() throws Exception {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
        if (new File(chr4BinFile).exists()) {
            try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(chr4BinFile)))) {
                variantMetadataIndexChr4 = (VariantMetadataIndex) in.readObject();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (new File(chr21BinFile).exists()) {
            try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(chr21BinFile)))) {
                variantMetadataIndexChr21 = (VariantMetadataIndex) in.readObject();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void findByMultipleVariantSpec_invalidSpec() {
        List<String> variants = List.of("chr21,5032061,A,G,NOTAGENE,missense_variant");
        Map<String, Set<String>> expectedResult = Map.of();
        Map<String, Set<String>> data = variantMetadataIndexChr21.findByMultipleVariantSpec(variants);

        assertEquals("Wrong number of records in response.", 1, data.size());
        assertEquals("The expected values were not found.", Set.of(), data.get("chr21,5032061,A,G,NOTAGENE,missense_variant"));
    }

    @Test
    public void findByMultipleVariantSpec_validSpec() {
        List<String> variants = List.of("chr21,5032061,A,G,LOC102723996,missense_variant");
        Map<String, Set<String>> expectedResult = Map.of(
            "chr21,5032061,A,G,LOC102723996,missense_variant",
            Set.of(
                "Gene_with_variant=LOC102723996;Variant_severity=MODERATE;Variant_consequence_calculated=missense_variant;Variant_class=SNV;Variant_frequency_in_gnomAD=0.0001346;Variant_frequency_as_text=Rare"
            )
        );
        Map<String, Set<String>> data = variantMetadataIndexChr21.findByMultipleVariantSpec(variants);

        assertEquals("Wrong number of records in response.", data.size(), 1);
        variants.stream().forEach(variant -> {
            assertEquals("The expected values were not found.", expectedResult.get(variant), data.get(variant));
        });
    }

    @Test
    public void findByMultipleVariantSpec_validSpecs() {
        List<String> variants =
            List.of("chr21,5032061,A,G,LOC102723996,missense_variant", "chr21,5033914,A,G,LOC102723996,missense_variant");
        Map<String, Set<String>> expectedResult = Map.of(
            "chr21,5032061,A,G,LOC102723996,missense_variant",
            Set.of(
                "Gene_with_variant=LOC102723996;Variant_severity=MODERATE;Variant_consequence_calculated=missense_variant;Variant_class=SNV;Variant_frequency_in_gnomAD=0.0001346;Variant_frequency_as_text=Rare"
            ), "chr21,5033914,A,G,LOC102723996,missense_variant",
            Set.of(
                "Gene_with_variant=LOC102723996;Variant_severity=MODERATE;Variant_consequence_calculated=missense_variant;Variant_class=SNV;Variant_frequency_in_gnomAD=0.0009728;Variant_frequency_as_text=Rare"
            )
        );
        Map<String, Set<String>> data = variantMetadataIndexChr21.findByMultipleVariantSpec(variants);

        assertEquals("Wrong number of records in response.", data.size(), 2);
        variants.stream().forEach(variant -> {
            assertEquals("The expected values were not found.", expectedResult.get(variant), data.get(variant));
        });
    }

    @Test
    public void testMultipleVariantSpecSamePOS() {

        List<String> variants = List.of(spec1, spec4);
        Map<String, Set<String>> expectedResult = Map.of(spec1, Set.of(spec1Info), spec4, Set.of(spec4Info));
        Map<String, Set<String>> data = variantMetadataIndexChr4.findByMultipleVariantSpec(variants);

        assertEquals("Wrong number of records in response.", data.size(), 2);
        variants.stream().forEach(variant -> {
            assertEquals("The expected values were not found.", expectedResult.get(variant), data.get(variant));
        });
    }

    @Test
    public void testMultipleVariantSpecSamePOSAndREF() {
        List<String> variants = List.of(spec1, spec5);
        Map<String, Set<String>> expectedResult = Map.of(spec1, Set.of(spec1Info), spec5, Set.of(spec5Info));
        Map<String, Set<String>> data = variantMetadataIndexChr4.findByMultipleVariantSpec(variants);

        assertEquals("Wrong number of records in response.", data.size(), 2);
        variants.stream().forEach(variant -> {
            assertEquals("The expected values were not found.", expectedResult.get(variant), data.get(variant));
        });
    }

    @Test
    public void testMultipleVariantSpecSamePOSAndALT() {
        List<String> variants = List.of(spec1, spec2);
        Map<String, Set<String>> expectedResult = Map.of(spec1, Set.of(spec1Info), spec2, Set.of(spec2Info));
        Map<String, Set<String>> data = variantMetadataIndexChr4.findByMultipleVariantSpec(variants);

        assertEquals("Wrong number of records in response.", data.size(), 2);
        variants.stream().forEach(variant -> {
            assertEquals("The expected values were not found.", expectedResult.get(variant), data.get(variant));
        });
    }

    /**
     * The google API that we use throws an IllegalStateException on duplicate entries
     */
    @Test
    public void testMultipleVariantSpecSameSpec() {
        assertThrows(IllegalStateException.class, () -> {
            List<String> variants = List.of(spec1, spec1);
            Map<String, Set<String>> expectedResult = Map.of(spec1, Set.of(spec1Info));
            Map<String, Set<String>> data = variantMetadataIndexChr4.findByMultipleVariantSpec(variants);

            assertEquals("Wrong number of records in response.", data.size(), 1);
            variants.stream().forEach(variant -> {
                assertEquals("The expected values were not found.", expectedResult.get(variant), data.get(variant));
            });
        });
    }

    @Test
    public void testVariantSpecMapSorting() {
        Map<String, String[]> specMap = Map.of(spec1, new String[] {spec1Info}, spec2, new String[] {spec2Info});

        TreeMap<String, String[]> metadataSorted = new TreeMap<>((o1, o2) -> {
            return new VariantSpec(o1).compareTo(new VariantSpec(o2));
        });
        metadataSorted.putAll(specMap);

        assertEquals("Wrong number of records in response.", metadataSorted.size(), 2);
        assertNotNull("spec1 value not present in the sorted map", metadataSorted.get(spec1));
        assertEquals("Incorrect spec1 value in the sorted map", spec1Info, metadataSorted.get(spec1)[0]);
        assertNotNull("spec2 value not present in the sorted map", metadataSorted.get(spec2));
        assertEquals("Incorrect spec2 value in the sorted map", spec2Info, metadataSorted.get(spec2)[0]);
    }



}
