package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.NewVCFLoader;

/**
 * These tests are in the ETL project so that we can read in data from disk each time instead of storing binfiles
 * that may become outdated.
 * @author nchu
 *
 */
public class BucketIndexBySampleTest {

	private static final String VCF_INDEX_FILE = "./src/test/resources/bucketIndexBySampleTest_vcfIndex.tsv";
	private static final String STORAGE_DIR = "./target/";
	private static final String MERGED_DIR = "./target/merged/";
	
	private static VariantStore variantStore;
	private static BucketIndexBySample bucketIndexBySample;
	
	//Some known variant specs from the input file. 
	private static final String spec1 = "4,9856624,CAAAAA,C";  	
	private static final String spec2 = "4,9856624,CAAA,C";		
	private static final String spec3 = "4,9856624,CA,C";		
	private static final String spec4 = "4,9856624,C,CA";		
	private static final String spec5 = "4,9856624,CAAAAA,CA";	
	
	private static final String spec6 = "14,19000060,C,G";
	private static final String spec7 = "14,19000152,C,T";
	private static final String spec8 = "14,19007733,C,T";
	private static final String spec9 = "14,19003660,T,C";
	
//  ## Patient 1 - NO variants
//	## Patient 2 - ALL variants
//	## Patient 3 - NO CHR 14 variants, ALL CHR 4 variants																		
//	## Patient 4 - ALL CHR 14 variants, NO CHR 4 variants
//	## others mixed
//	patient 5 has spec 1 and 5
//	patient 6 has spec 4 and 5

	
	//these parameters to the BucketIndexBySample methods are configured by each test
	Set<String>  variantSet;
	List<Integer> patientSet;
	
	@BeforeClass
	public static void initializeBinfile() throws Exception {
		//load variant data
		NewVCFLoader.main(new String[] {VCF_INDEX_FILE, STORAGE_DIR, MERGED_DIR});	
				
		//read in variantStore object created by VCFLoader
		ObjectInputStream ois = new ObjectInputStream(new GZIPInputStream(new FileInputStream(STORAGE_DIR + "variantStore.javabin")));
		variantStore = (VariantStore) ois.readObject();
		ois.close();
		variantStore.open();	
		
		//now use that object to initialize the BucketIndexBySample object
		bucketIndexBySample = new BucketIndexBySample(variantStore, STORAGE_DIR);
//		bucketIndexBySample.printPatientMasks();
	}
	
	@Before
	public void setUpTest() {
		//start with fresh, empty collections
		variantSet = new HashSet<String>();
		patientSet = new ArrayList<Integer>();
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_noPatients() throws IOException {
		variantSet.add(spec1);
		variantSet.add(spec2);
		variantSet.add(spec3);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Empty Patient List should filter out all variants", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_noVariants() throws IOException {
		patientSet.add(1);
		patientSet.add(2);
		patientSet.add(3);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Empty Variant Set should remain empty", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_VariantsWithoutPatientsLastBucket() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_VariantsWithoutPatientsLastBucket");
		
		variantSet.add(spec5);
		
		patientSet.add(1);
		patientSet.add(4);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Patients should not match any variants in the list", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_PatientsWithNoVariantsFirstBucket() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_PatientsWithNoVariantsFirstBucket");
		
		variantSet.add(spec7);
		variantSet.add(spec8);
		
		patientSet.add(1);
		patientSet.add(3);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Patients should not match any variants in the list", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_allValidLastBucket() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_allValidLastBucket");
		
		//specs 1-5 are in the last bucket 
		variantSet.add(spec1);
		variantSet.add(spec4);
		variantSet.add(spec5);
		
		patientSet.add(2);
		patientSet.add(4);
		patientSet.add(5);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("No variants should be filtered out", (long)3, (long)filteredVariantSet.size());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_allValidFirstBucket() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_allValidFirstBucket");
		
		//specs 1-5 are in the last bucket 
		variantSet.add(spec6);
		variantSet.add(spec7);
		variantSet.add(spec8);
		
		patientSet.add(2);
		patientSet.add(3);
		patientSet.add(5);
		patientSet.add(6);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("No variants should be filtered out", (long)3, (long)filteredVariantSet.size());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_someValid() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_someValid");
		
		//specs 1-5 are in the last bucket 
		variantSet.add(spec1);
		variantSet.add(spec6);
		
		patientSet.add(1);
		patientSet.add(3);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("One variant should be filtered out", (long)1, (long)filteredVariantSet.size());
		assertTrue("Expected variant not found", filteredVariantSet.contains(spec1));
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_allValidDifferentPatients() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_allValidDifferentPatients");
		
		//specs 1-5 are in the last bucket 
		variantSet.add(spec1);  // only 5
		variantSet.add(spec4);  // only 6
		variantSet.add(spec5);  // 5 & 6
		variantSet.add(spec7);  // only #4
		
		patientSet.add(4);
		patientSet.add(5);
		patientSet.add(6);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("No variants should be filtered out", (long)4, (long)filteredVariantSet.size());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_someValidDifferentPatients() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_allValidDifferentPatients");
		
		//specs 1-5 are in the last bucket 
		variantSet.add(spec1); 
		variantSet.add(spec4);  
		variantSet.add(spec5);  
		variantSet.add(spec8);  //none
		variantSet.add(spec9);
		
		patientSet.add(3);
		patientSet.add(9);
		patientSet.add(10);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("No variants should be filtered out", (long)4, (long)filteredVariantSet.size());
		assertFalse("Spec 8 should have been filtered out", filteredVariantSet.contains(spec8));
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_HeteroPatientA() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_HeteroPatientA");
		
		variantSet.add(spec8);  //patients 7 and 8 have hetero flags for this variant (1|0 and 0|1)
		
		patientSet.add(7);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("No variants should be filtered out", (long)1, (long)filteredVariantSet.size());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_HeteroPatientB() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_HeteroPatientB");
		
		variantSet.add(spec8);  //patients 7 and 8 have hetero flags for this variant (1|0 and 0|1)
		
		patientSet.add(8);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("No variants should be filtered out", (long)1, (long)filteredVariantSet.size());
	}
	
}
