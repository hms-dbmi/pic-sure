package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

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
	private static final String STORAGE_DIR = "./target";
	private static final String MERGED_DIR = "./target/merged";
	
	private static VariantStore variantStore;
	private static BucketIndexBySample bucketIndexBySample;
	
	
	//Some known variant specs from the input file. 
	private static final String spec1 = "4,9856624,CAAAAA,C";  	
	private static final String spec2 = "4,9856624,CAAA,C";		
	private static final String spec3 = "4,9856624,CA,C";		
	private static final String spec4 = "4,9856624,C,CA";		
	private static final String spec5 = "4,9856624,CAAAAA,CA";	// most patients have this variant
	
	//patient 1 and 4 should have 0 variants of these specs
	// 5 has 1 and 5
	// 6 has 4 and 5
	
	//## Patient 1 - NO variants
//	## Patient 2 - ALL variants
//	## Patient 3 - All CHR 14 variants, NO CHR 4 variants
//	## Patient 4 - ALL CHR 4 variants, no CHR 14 variants
//	## others mixed


	
	//610 and 625 should have none of these 
	//618 and 614 should have spec6 only
	//588 should have spec 6 and 7
	//413 should have 6 and 8 
	private static final String spec6 = "14,19000060,C,G";
	private static final String spec7 = "14,19000152,C,T";
	private static final String spec8 = "14,19000161,G,T";
	
	
//	//From file 2 14	21089541	rs543976440	A	G
//	private static final String spec7 = "14,21089541,rs543976440,A,G";
//	//From file 3 14	21616876	rs549724318	G	A
//	private static final String spec8 = "14,21616876,rs549724318,G,A";
	
	@BeforeClass
	public static void initializeBinfile() throws Exception {
		//load variant data
		NewVCFLoader.main(new String[] {VCF_INDEX_FILE, STORAGE_DIR, MERGED_DIR});	
				
		//read in variantStore object created by VCFLoader
		ObjectInputStream ois = new ObjectInputStream(new GZIPInputStream(new FileInputStream(STORAGE_DIR + "/variantStore.javabin")));
		variantStore = (VariantStore) ois.readObject();
		ois.close();
		variantStore.open();	
		
		//now use that object to initialize the BucketIndexBySample object
		bucketIndexBySample = new BucketIndexBySample(variantStore, STORAGE_DIR);
		
		
//		bucketIndexBySample.printPatientMasks();
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_noPatients() throws IOException {
		
		Set<String>  variantSet = new HashSet<String>();
		List<Integer> patientSet = new ArrayList<Integer>();
		
		variantSet.add(spec1);
		variantSet.add(spec2);
		variantSet.add(spec3);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Empty Patient List should filter out all variants", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_noVariants() throws IOException {
		Set<String>  variantSet = new HashSet<String>();
		List<Integer> patientSet = new ArrayList<Integer>();
		
		patientSet.add(1);
		patientSet.add(2);
		patientSet.add(3);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Empty Variant Set should remain empty", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_VariantsWithoutPatients() throws IOException {
		
		System.out.println("test_filterVariantSetForPatientSet_VariantsWithoutPatients");
		Set<String>  variantSet = new HashSet<String>();
		List<Integer> patientSet = new ArrayList<Integer>();
		
		variantSet.add(spec5);
		
		patientSet.add(1);
		patientSet.add(4);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Patients should not match any variants in the list", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_PatientsWithNoVariantsFirstBucket() throws IOException {
		
		System.out.println("test_filterVariantSetForPatientSet_PatientsWithNoVariantsFirstBucket");
		Set<String>  variantSet = new HashSet<String>();
		List<Integer> patientSet = new ArrayList<Integer>();
		
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
		
		Set<String>  variantSet = new HashSet<String>();
		List<Integer> patientSet = new ArrayList<Integer>();
		
		
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
		
		//610 should have none of these 
		//618 and 614 should have spec6 only
		//588 should have spec 6 and 7
		//413 should have 6 and 8 
		
		System.out.println("test_filterVariantSetForPatientSet_allValidFirstBucket");
		
		Set<String>  variantSet = new HashSet<String>();
		List<Integer> patientSet = new ArrayList<Integer>();
		
		
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
		
		System.out.println("test_filterVariantSetForPatientSet_allValidLastBucket");
		
		Set<String>  variantSet = new HashSet<String>();
		List<Integer> patientSet = new ArrayList<Integer>();
		
		
		//specs 1-5 are in the last bucket 
		variantSet.add(spec1);
		variantSet.add(spec6);
		
		patientSet.add(1);
		patientSet.add(3);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("One variant should be filtered out", (long)1, (long)filteredVariantSet.size());
	}
	
	
}
