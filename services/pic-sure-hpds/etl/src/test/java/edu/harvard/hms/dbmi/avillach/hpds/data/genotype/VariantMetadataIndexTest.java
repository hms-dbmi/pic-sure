package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.VariantMetadataLoader;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.event.annotation.BeforeTestClass;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.util.AssertionErrors.assertEquals;
import static org.springframework.test.util.AssertionErrors.assertNotNull;

public class VariantMetadataIndexTest {

	//From file 1 14	19038291	rs550062154	C	A
	//From file 2 14	21089541	rs543976440	A	G
	//From file 3 14	21616876	rs549724318	G	A
	
	
	/**
	 * The metadataIndex is non-mutable (or should be) so we only need one object to test
	 */
	private static VariantMetadataIndex vmi;
	public static String binFile = "target/VariantMetadata.javabin";
	VariantBucketHolder<String[]> bucketCache = new VariantBucketHolder<String[]>();
	
	//Some known variant specs from the input file.  These have been designed for testing partially overlapping specs
	private static final String spec1 = "4,9856624,CAAAAA,C";  	private static final String spec1Info = "AC=401;AF=8.00719e-02;NS=2504;AN=5008;EAS_AF=3.37000e-02;EUR_AF=4.97000e-02;AFR_AF=1.64100e-01;AMR_AF=3.75000e-02;SAS_AF=7.57000e-02;DP=18352;AA=G|||;VT=SNP";
	private static final String spec2 = "4,9856624,CAAA,C";		private static final String spec2Info = "AC=62;AF=1.23802e-02;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=1.00000e-03;AFR_AF=4.54000e-02;AMR_AF=1.40000e-03;SAS_AF=0.00000e+00;DP=18328;AA=T|||;VT=SNP";
	private static final String spec3 = "4,9856624,CA,C";		private static final String spec3Info = "AC=8;AF=1.59744e-03;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=0.00000e+00;AFR_AF=6.10000e-03;AMR_AF=0.00000e+00;SAS_AF=0.00000e+00;DP=18519;AA=T|||;VT=SNP";
	private static final String spec4 = "4,9856624,C,CA";		private static final String spec4Info = "AC=75;AF=1.49760e-02;NS=2504;AN=5008;EAS_AF=3.27000e-02;EUR_AF=2.49000e-02;AFR_AF=6.80000e-03;AMR_AF=4.30000e-03;SAS_AF=5.10000e-03;DP=18008;AA=A|||;VT=SNP";
	private static final String spec5 = "4,9856624,CAAAAA,CA";	private static final String spec5Info = "AC=3033;AF=6.05631e-01;NS=2504;AN=5008;EAS_AF=5.23800e-01;EUR_AF=7.54500e-01;AFR_AF=4.28900e-01;AMR_AF=7.82400e-01;SAS_AF=6.50300e-01;DP=20851;VT=INDEL";
	

	@BeforeTestClass
	public static void initializeBinfile() throws Exception {
		VariantMetadataLoader.main(new String[] {"./src/test/resources/test_vcfIndex.tsv", binFile, "target/VariantMetadataStorage.bin"});
		
		if(new File(binFile).exists()) {
			try(ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(binFile)))){
				vmi = (VariantMetadataIndex) in.readObject();
			}catch(Exception e) {
				e.printStackTrace();
			}
		} 
	}
	
	@Test
	public void test_2a_variantFromFile_1_WasLoaded() {
		String[] data = vmi.findBySingleVariantSpec("14,19038291,C,A", bucketCache); 
		String[] expecteds = {"AC=14;AF=2.79553e-03;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=1.09000e-02;AFR_AF=0.00000e+00;AMR_AF=4.30000e-03;SAS_AF=0.00000e+00;DP=32694;AA=.|||;VT=SNP"};
		assertEquals("The expected values were not found.", expecteds, data);
	}

	@Test
	public void test_2b_variantFromFile_2_WasLoaded() {
		String[] data = vmi.findBySingleVariantSpec("14,21089541,A,G", bucketCache); 
		String[] expecteds = {"AC=20;AF=3.99361e-03;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=0.00000e+00;AFR_AF=1.44000e-02;AMR_AF=1.40000e-03;SAS_AF=0.00000e+00;DP=18507;AA=A|||;VT=SNP"};
		assertEquals("The expected values were not found.", expecteds, data);
	}

	@Test
	public void test_2c_variantFromFile_3_WasNotLoaded() {
		String[] data = vmi.findBySingleVariantSpec("14,21616876,G,A", bucketCache); 
		String[] expecteds = {};
		assertEquals("The expected values were not found.", expecteds, data);
	}
	
	@Test
	public void test_4_MultipleVariantSpec() {
		List<String> variants = List.of("14,19038291,C,A", "14,21089541,A,G");
		Map<String,String[]> expectedResult = Map.of(
				"14,19038291,C,A"
				, new String[]{"AC=14;AF=2.79553e-03;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=1.09000e-02;AFR_AF=0.00000e+00;AMR_AF=4.30000e-03;SAS_AF=0.00000e+00;DP=32694;AA=.|||;VT=SNP"}
				,"14,21089541,A,G"
				,new String[]{"AC=20;AF=3.99361e-03;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=0.00000e+00;AFR_AF=1.44000e-02;AMR_AF=1.40000e-03;SAS_AF=0.00000e+00;DP=18507;AA=A|||;VT=SNP"});
		Map<String, String[]>[] data = new Map[] {vmi.findByMultipleVariantSpec(variants)}; 
		
		assertEquals("Wrong number of records in response.", data[0].size(), 2);
		variants.stream().forEach(variant->{
			assertEquals("The expected values were not found.", expectedResult.get(variant), data[0].get(variant));
		});
		
		Map<String, String[]>[] data2 = new Map[] {vmi.findByMultipleVariantSpec(variants.subList(0, 1))}; 
		
		assertEquals("Wrong number of records in response.", 1, data2[0].size());
		assertEquals("The expected values were not found.", expectedResult.get(variants.get(0)), data2[0].get(variants.get(0)));

	}
	
	@Test
	public void testMultipleVariantSpecSamePOS() {
		
		List<String> variants = List.of(spec1, spec4);
		Map<String,String[]> expectedResult = Map.of(
				spec1, new String[]{spec1Info},
				spec4, new String[]{spec4Info});
		Map<String, String[]>[] data = new Map[] {vmi.findByMultipleVariantSpec(variants)}; 
		
		assertEquals("Wrong number of records in response.", data[0].size(), 2);
		variants.stream().forEach(variant->{
			assertEquals("The expected values were not found.", expectedResult.get(variant), data[0].get(variant));
		});
	}
	
	@Test
	public void testMultipleVariantSpecSamePOSAndREF() {
		List<String> variants = List.of(spec1, spec5);
		Map<String,String[]> expectedResult = Map.of(
				spec1, new String[]{spec1Info},
				spec5, new String[]{spec5Info});
		Map<String, String[]>[] data = new Map[] {vmi.findByMultipleVariantSpec(variants)}; 
		
		assertEquals("Wrong number of records in response.", data[0].size(), 2);
		variants.stream().forEach(variant->{
			assertEquals("The expected values were not found.", expectedResult.get(variant), data[0].get(variant));
		});
	}
	
	@Test
	public void testMultipleVariantSpecSamePOSAndALT() {
		List<String> variants = List.of(spec1, spec2);
		Map<String,String[]> expectedResult = Map.of(
				spec1, new String[]{spec1Info},
				spec2, new String[]{spec2Info});
		Map<String, String[]>[] data = new Map[] {vmi.findByMultipleVariantSpec(variants)}; 
		
		assertEquals("Wrong number of records in response.", data[0].size(), 2);
		variants.stream().forEach(variant->{
			assertEquals("The expected values were not found.", expectedResult.get(variant), data[0].get(variant));
		});
	}
	
	/**
	 * The google API that we use throws an IllegalStateException on duplicate entries
	 */
	@Test
	public void testMultipleVariantSpecSameSpec() {
		assertThrows(IllegalStateException.class, () -> {
			List<String> variants = List.of(spec1, spec1);
			Map<String,String[]> expectedResult = Map.of(
					spec1, new String[]{spec1Info});
			Map<String, String[]>[] data = new Map[] {vmi.findByMultipleVariantSpec(variants)};

			assertEquals("Wrong number of records in response.", data[0].size(), 1);
			variants.stream().forEach(variant->{
				assertEquals("The expected values were not found.", expectedResult.get(variant), data[0].get(variant));
			});
		});
	}
	
	@Test
	public void testVariantSpecMapSorting() {
		Map<String,String[]> specMap  = Map.of(
				spec1, new String[]{spec1Info},
				spec2, new String[]{spec2Info});
		
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
