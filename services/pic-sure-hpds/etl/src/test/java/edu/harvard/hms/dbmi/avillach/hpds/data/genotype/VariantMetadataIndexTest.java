package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.VariantMetadataLoader;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VariantMetadataIndexTest {

	//From file 1 14	19038291	rs550062154	C	A
	//From file 2 14	21089541	rs543976440	A	G
	//From file 3 14	21616876	rs549724318	G	A
	private VariantMetadataIndex vmi;
	public static String binFile = "target/VariantMetadata.javabin";
	VariantBucketHolder<String[]> bucketCache = new VariantBucketHolder<String[]>();

	@Before
	public void initialize() throws IOException, ClassNotFoundException {
		if(new File(binFile).exists()) {
			try(ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(binFile)))){
				vmi = (VariantMetadataIndex) in.readObject();
			}catch(Exception e) {
				e.printStackTrace();
			}
		} 
	}
	
	@Test
	public void test_1_VariantMetadataLoader() throws Exception {
		VariantMetadataLoader.main(new String[] {"./src/test/resources/test_vcfIndex.tsv", binFile, "target/VariantMetadataStorage.bin"});
	}
	
	@Test
	public void test_2a_variantFromFile_1_WasLoaded() {
		String[] data = vmi.findBySingleVariantSpec("14,19038291,C,A", bucketCache); 
		String[] expecteds = {"AC=14;AF=2.79553e-03;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=1.09000e-02;AFR_AF=0.00000e+00;AMR_AF=4.30000e-03;SAS_AF=0.00000e+00;DP=32694;AA=.|||;VT=SNP"};
		assertArrayEquals("The expected values were not found.", expecteds, data);
	}

	@Test
	public void test_2b_variantFromFile_2_WasLoaded() {
		String[] data = vmi.findBySingleVariantSpec("14,21089541,A,G", bucketCache); 
		String[] expecteds = {"AC=20;AF=3.99361e-03;NS=2504;AN=5008;EAS_AF=0.00000e+00;EUR_AF=0.00000e+00;AFR_AF=1.44000e-02;AMR_AF=1.40000e-03;SAS_AF=0.00000e+00;DP=18507;AA=A|||;VT=SNP"};
		assertArrayEquals("The expected values were not found.", expecteds, data);
	}

	@Test
	public void test_2c_variantFromFile_3_WasNotLoaded() {
		String[] data = vmi.findBySingleVariantSpec("14,21616876,G,A", bucketCache); 
		String[] expecteds = {};
		assertArrayEquals("The expected values were not found.", expecteds, data);
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
			assertArrayEquals("The expected values were not found.", expectedResult.get(variant), data[0].get(variant));
		});
		
		Map<String, String[]>[] data2 = new Map[] {vmi.findByMultipleVariantSpec(variants.subList(0, 1))}; 
		
		assertEquals("Wrong number of records in response.", 1, data2[0].size());
		assertArrayEquals("The expected values were not found.", expectedResult.get(variants.get(0)), data2[0].get(variants.get(0)));

	}
}
