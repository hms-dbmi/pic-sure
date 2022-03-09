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

	private static final String VCF_INDEX_FILE = "./src/test/resources/test_vcfIndex.tsv";
	private static final String STORAGE_DIR = "./target";
	
	private static VariantStore variantStore;
	private static BucketIndexBySample bucketIndexBySample;
	
	
	//Some known variant specs from the input file. 
	private static final String spec1 = "4,9856624,CAAAAA,C";  	
	private static final String spec2 = "4,9856624,CAAA,C";		
	private static final String spec3 = "4,9856624,CA,C";		
	private static final String spec4 = "4,9856624,C,CA";		
	private static final String spec5 = "4,9856624,CAAAAA,CA";	// most patients have this variant
	
	//patient 610 and 618 should have 0 variants of these specs
	//614 has 1 and 5
	//663 has 4 and 5
	

	
	
	//From file 1 14	19038291	rs550062154	C	A
	private static final String spec6 = "14,19038291,rs550062154,C,A";
	//From file 2 14	21089541	rs543976440	A	G
	private static final String spec7 = "14,21089541,rs543976440,A,G";
	//From file 3 14	21616876	rs549724318	G	A
	private static final String spec8 = "14,21616876,rs549724318,G,A";
	
	@BeforeClass
	public static void initializeBinfile() throws Exception {
		//load variant data
		NewVCFLoader.main(new String[] {VCF_INDEX_FILE, STORAGE_DIR});	
				
		//read in variantStore object created by VCFLoader
		ObjectInputStream ois = new ObjectInputStream(new GZIPInputStream(new FileInputStream(STORAGE_DIR + "/variantStore.javabin")));
		variantStore = (VariantStore) ois.readObject();
		ois.close();
		variantStore.open();	
		
		//now use that object to initialize the BucketIndexBySample object
		bucketIndexBySample = new BucketIndexBySample(variantStore);
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
		
		patientSet.add(610);
		patientSet.add(618);
		patientSet.add(663);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Empty Variant Set should remain empty", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_VariantsWithoutPatients() throws IOException {
		
		System.out.println("test_filterVariantSetForPatientSet_VariantsWithoutPatients");
		Set<String>  variantSet = new HashSet<String>();
		List<Integer> patientSet = new ArrayList<Integer>();
		
		variantSet.add(spec5);
		
		patientSet.add(610);
		patientSet.add(618);
//		patientSet.add(663);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertTrue("Patients should not match any variants in the list", filteredVariantSet.isEmpty());
	}
	
	@Test
	public void test_filterVariantSetForPatientSet_allValid() throws IOException {
		
		System.out.println("test_filterVariantSetForPatientSet_allValid");
		
		Set<String>  variantSet = new HashSet<String>();
		List<Integer> patientSet = new ArrayList<Integer>();
		
		variantSet.add(spec1);
		variantSet.add(spec4);
		variantSet.add(spec5);
		
		
		patientSet.add(610);
		patientSet.add(614);
		patientSet.add(663);
		
		Collection<String> filteredVariantSet = bucketIndexBySample.filterVariantSetForPatientSet(variantSet, patientSet);
		
		assertEquals("No variants should be filtered out", (long)3, (long)filteredVariantSet.size());
	}
	
}
