package edu.harvard.hms.dbmi.avillach.hpds.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.BucketIndexBySample;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.springframework.test.util.AssertionErrors.*;

/**
 * These tests are in the ETL project so that we can read in data from disk each time instead of storing binfiles
 * that may become outdated.
 * 
 * the BucketIndexBySample.filterVariantSetForPatientSet method removes variants base on the patent BUCKET MASK;  just because a patient
 * does not have a particular variant doesn't mean it will be filtered out e.g., when a patient has a different variant in the same bucket.
 *
 * Filtering the specific variants is typically done by the calling function after filtering out the unneeded buckets.
 * 
 * @author nchu
 *
 */
public class BucketIndexBySampleTest {

	private static final String STORAGE_DIR = "./target/";

	private static BucketIndexBySample bucketIndexBySample;
	
	//Some known variant specs from the input file. 
	//Some known variant specs from the input file.  These have been designed for testing partially overlapping specs
	private static final String spec1 = "chr4,9856624,CAAAAA,C,TVP23A,splice_acceptor_variant";
	private static final String spec2 = "chr4,9856624,CAAA,C,TVP23A,splice_acceptor_variant";
	private static final String spec3 = "chr4,9856624,CA,C,TVP23A,splice_acceptor_variant";
	private static final String spec4 = "chr4,9856624,C,CA,TVP23A,splice_acceptor_variant";
	private static final String spec5 = "chr4,9856624,CAAAAA,CA,TVP23A,splice_acceptor_variant";
	
	private static final String spec6 = "chr21,5032061,A,G,LOC102723996,missense_variant";
	private static final String spec6b = "chr21,5032061,A,G,ABCDEF123456,synonymous_variant";
	private static final String spec7 = "chr21,5033914,A,G,LOC102723996,missense_variant";
	private static final String spec8 = "chr21,5033988,C,G,LOC102723996,synonymous_variant";
	private static final String spec9 = "chr21,5034028,C,T,LOC102723996,missense_variant";

	
	//these parameters to the BucketIndexBySample methods are configured by each test
	Set<String>  variantSet;
	List<Integer> patientSet;
	
	@BeforeAll
	public static void initializeBinfile() throws Exception {
		BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
		VariantStore variantStore = VariantStore.readInstance(STORAGE_DIR);
		
		//now use that object to initialize the BucketIndexBySample object
		bucketIndexBySample = new BucketIndexBySample(variantStore, STORAGE_DIR);
	}
	
	@BeforeEach
	public void setUpTest() {
		//start with fresh, empty collections
		variantSet = new HashSet<>();
		patientSet = new ArrayList<>();
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
		patientSet.add(200392);
		patientSet.add(200689);
		patientSet.add(200972);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Empty Variant Set should remain empty", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_VariantsWithoutPatientsLastBucket() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_VariantsWithoutPatientsLastBucket");
		
		variantSet.add(spec1);
		variantSet.add(spec2);
		variantSet.add(spec3);
		variantSet.add(spec4);
		variantSet.add(spec5);

		patientSet.add(200706);
		patientSet.add(200709);

		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Patients should not match any variants in the list", filteredVariantSet.isEmpty());
	}

	@Test
	public void test_filterVariantSetForPatientSet_PatientsWithNoVariantsFirstBucket() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_PatientsWithNoVariantsFirstBucket");

		variantSet.add(spec6);
		variantSet.add(spec6b);

		patientSet.add(197506);
		patientSet.add(197508);
		patientSet.add(197509);

		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);

		assertTrue("Patients should not match any variants in the list", filteredVariantSet.isEmpty());
	}
	@Test
	public void test_filterVariantSetForPatientSet_PatientsWithNoVariantsFirstBucketNoCall() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_PatientsWithNoVariantsFirstBucket");

		variantSet.add("chr20,5032061,A,G,LOC102723996,missense_variant");
		variantSet.add("chr21,5032061,A,G,ABCDEF123456,synonymous_variant");

		patientSet.add(197506);
		patientSet.add(197508);
		patientSet.add(197509);

		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);

		assertTrue("Patients should not match any variants in the list", filteredVariantSet.isEmpty());
	}

	@Test
	public void test_filterVariantSetForPatientSet_allValidFirstBucket() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_allValidFirstBucket");

		variantSet.add(spec6);
		variantSet.add(spec6b);

		patientSet.add(200392);
		patientSet.add(200689);
		patientSet.add(200972);

		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);

		assertEquals("No variants should be filtered out", 2, filteredVariantSet.size());
	}
	@Test
	public void test_filterVariantSetForPatientSet_allValidFirstBucketWithNoCall() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_allValidFirstBucket");

		variantSet.add("chr20,5032061,A,G,ABC1,missense_variant");
		variantSet.add("chr20,5032061,A,G,DEF1,synonymous_variant");

		patientSet.add(200392);
		patientSet.add(200689);
		patientSet.add(200972);

		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);

		assertEquals("No variants should be filtered out", 2, filteredVariantSet.size());
	}
	
	@Test
	@Disabled
	public void test_filterVariantSetForPatientSet_someValid() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_someValid");

		variantSet.add(spec2);
		variantSet.add(spec6);

		patientSet.add(200392);
		patientSet.add(200689);
		patientSet.add(200972);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("One variant should be filtered out", 1, filteredVariantSet.size());
		assertTrue("Expected variant not found", filteredVariantSet.contains(spec1));
	}

	@Test
	public void test_filterVariantSetForPatientSet_allValidDifferentPatients() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_allValidDifferentPatients");

		variantSet.add(spec1);
		variantSet.add(spec4);
		variantSet.add(spec5);
		variantSet.add(spec7);

		patientSet.add(200194);
		patientSet.add(200450);
		patientSet.add(200710);
		patientSet.add(198206);

		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);

		assertEquals("No variants should be filtered out", (long)4, (long)filteredVariantSet.size());
	}
	
	@Test
	@Disabled
	public void test_filterVariantSetForPatientSet_someValidDifferentPatients() throws IOException {
		System.out.println("test_filterVariantSetForPatientSet_allValidDifferentPatients");
		
		//specs 1-5 are in the last bucket 
		variantSet.add(spec1); 
		variantSet.add(spec4);  
		variantSet.add(spec5);  
		variantSet.add(spec8);  
		variantSet.add(spec9); //none
		
		patientSet.add(3);
		patientSet.add(9);
		patientSet.add(10);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("One variant should be filtered out", (long)4, (long)filteredVariantSet.size());
		assertFalse("Spec 9 should have been filtered out", filteredVariantSet.contains(spec9));
	}
	
}
