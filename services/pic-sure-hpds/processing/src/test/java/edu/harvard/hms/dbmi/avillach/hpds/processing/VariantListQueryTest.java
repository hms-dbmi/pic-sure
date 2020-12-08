package edu.harvard.hms.dbmi.avillach.hpds.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

import org.junit.Test;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;

public class VariantListQueryTest {

	public class TestableVariantListProcessor extends VariantListProcessor {
		private List<ArrayList<Set<String>>> testVariantSets;
		private int callCount = 0;
		
		
		public TestableVariantListProcessor(boolean isOnlyForTests, ArrayList<Set<String>> testVariantSets)
				throws ClassNotFoundException, FileNotFoundException, IOException {
			this(isOnlyForTests, List.of(testVariantSets));
		}

		public TestableVariantListProcessor(boolean isOnlyForTests, List<ArrayList<Set<String>>> testVariantSets)
				throws ClassNotFoundException, FileNotFoundException, IOException {
			super(isOnlyForTests);
			this.testVariantSets = testVariantSets;
			//we still need an object to reference when checking the variant store, even if it's empty.
			variantStore = new VariantStore();
			variantStore.setPatientIds(new String[0]);
			allIds = new TreeSet<>(Set.of(10001,20002));
		}

		public void addVariantsMatchingFilters(VariantInfoFilter filter, ArrayList<Set<String>> variantSets) {
			for (Set<String> set : testVariantSets.get(callCount++ % testVariantSets.size())) {
				System.out.println("Adding " + Arrays.deepToString(set.toArray()));
				variantSets.add(set);
			}
		}

	}

	@Test
	public void testVariantListWithEmptyQuery() throws Exception {
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, new ArrayList<Set<String>>());
		assertEquals("[]", t.runVariantListQuery(new Query()));
	} 
	
	@Test
	public void testVariantListWithNullVariantInfoFiltersInQuery() throws Exception {
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, new ArrayList<Set<String>>());
		Query query = new Query();
		query.variantInfoFilters = null;
		assertEquals("[]", t.runVariantListQuery(query));
	}	
	
	@Test
	public void testVariantListWithVariantInfoFiltersWithMultipleVariantsButNoIntersectingKeys() throws Exception {
		ArrayList<Set<String>> data = new ArrayList<Set<String>>(List.of(
				Set.of("2,1234,G,T"), 
				Set.of("2,5678,C,A")));
		
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = 
				Map.of("FILTERKEY", new String[] {"test1"});
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = List.of(variantInfoFilter);

		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		assertEquals("[]", t.runVariantListQuery(q));
	}	
	
	@Test
	public void testVariantListWithVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		ArrayList<Set<String>> data = new ArrayList<Set<String>>(List.of(
				Set.of("2,1234,G,T"), 
				Set.of("2,1234,G,T","2,3456,C,A")));		

		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		String runVariantListQuery = t.runVariantListQuery(q);
		assertEquals("[2,1234,G,T]", runVariantListQuery);
	}	
	
	@Test
	public void testVariantListWithTwoVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		List<ArrayList<Set<String>>> data = new ArrayList<ArrayList<Set<String>>>(new ArrayList(
				List.of(new ArrayList(List.of(Set.of("2,1234,G,T", "3,10000,C,T"))),
						new ArrayList(List.of(Set.of("2,1234,G,T", "2,3456,C,A"))))));
		
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);
		
		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		VariantInfoFilter variantInfoFilter2 = new VariantInfoFilter();
		variantInfoFilter2.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>(
				List.of(variantInfoFilter, variantInfoFilter2));
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		
		//use the collection return value here, since order can change (with parallel processing)
		Collection<String> variantList = t.getVariantList(q);
		//should return a list of all variants
		
		assertTrue(variantList.contains("3,10000,C,T"));
		assertTrue(variantList.contains("2,1234,G,T"));
		assertTrue(variantList.contains("2,3456,C,A"));
	}
	
	@Test
	public void testVariantListWithVariantInfoFiltersWithOnlyOneFilterCriteria() throws Exception {
		ArrayList<Set<String>> data = new ArrayList<Set<String>>(List.of(
				Set.of("2,1234,G,T")));
		
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		String runVariantListQuery = t.runVariantListQuery(q);
		assertEquals("[2,1234,G,T]", runVariantListQuery);
	}
	
	@Test
	public void testVariantListtWithVariantInfoFiltersWhenFiltersDoNotMatchAnyVariants() throws Exception {
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, new ArrayList<Set<String>>());

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		String runVariantListQuery = t.runVariantListQuery(q);
		assertEquals("[]", runVariantListQuery);
	}	

}
