package edu.harvard.hms.dbmi.avillach.hpds.processing;

import static org.junit.Assert.assertEquals;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

import org.junit.Test;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;

public class CountProcessorTest {

	public class TestableCountProcessor extends CountProcessor {
		private List<ArrayList<Set<String>>> testVariantSets;
		private int callCount = 0;
		
		
		public TestableCountProcessor(boolean isOnlyForTests, ArrayList<Set<String>> testVariantSets)
				throws ClassNotFoundException, FileNotFoundException, IOException {
			this(isOnlyForTests, List.of(testVariantSets));
		}

		public TestableCountProcessor(boolean isOnlyForTests, List<ArrayList<Set<String>>> testVariantSets)
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
	public void testVariantCountWithEmptyQuery() throws Exception {
		TestableCountProcessor t = new TestableCountProcessor(true, new ArrayList<Set<String>>());
		Map<String, Object> countResponse = t.runVariantCount(new Query());
		assertEquals("0",countResponse.get("count") );
	}

	@Test
	public void testVariantCountWithEmptyVariantInfoFiltersInQuery() throws Exception {
		TestableCountProcessor t = new TestableCountProcessor(true, new ArrayList<Set<String>>());
		Query query = new Query();
		query.variantInfoFilters = new ArrayList<>();
		Map<String, Object> countResponse = t.runVariantCount(query);
		assertEquals("0",countResponse.get("count") );
	}

	@Test
	public void testVariantCountWithVariantInfoFiltersWithMultipleVariantsButNoIntersectionKeys() throws Exception {
		ArrayList<Set<String>> data = new ArrayList<Set<String>>(List.of(
				Set.of("2,1234,G,T"), 
				Set.of("2,5678,C,A")));

		TestableCountProcessor t = new TestableCountProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = 
				Map.of("FILTERKEY", new String[] {"test1"});
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = List.of(variantInfoFilter);

		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		
		Map<String, Object> countResponse = t.runVariantCount(q);
		assertEquals(0,countResponse.get("count") );
	}

	@Test
	public void testVariantCountWithVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		ArrayList<Set<String>> data = new ArrayList<Set<String>>(List.of(
				Set.of("2,1234,G,T"),
				Set.of("2,1234,G,T","2,5678,C,A"))); 
		TestableCountProcessor t = new TestableCountProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" });
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		
		Map<String, Object> countResponse = t.runVariantCount(q);
		assertEquals(1,countResponse.get("count") );
	}

	@Test
	public void testVariantCountWithTwoVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		List<ArrayList<Set<String>>> data1 = new ArrayList<ArrayList<Set<String>>>(new ArrayList(List.of(
				new ArrayList(List.of(Set.of("2,1234,G,T", "3,10000,C,T"))),new ArrayList(List.of(Set.of("2,1234,G,T", "2,5678,C,A"))))));
		TestableCountProcessor t = new TestableCountProcessor(true, data1);
		
		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" });
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		VariantInfoFilter variantInfoFilter2 = new VariantInfoFilter();
		variantInfoFilter2.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>(
				List.of(variantInfoFilter, variantInfoFilter2));
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		
		
		Map<String, Object> countResponse = t.runVariantCount(q);
		assertEquals(3,countResponse.get("count") );
	}

	@Test
	public void testVariantCountWithVariantInfoFiltersWithOnlyOneFilterCriteria() throws Exception {
		ArrayList<Set<String>> data = new ArrayList(List.of(
				Set.of("2,1234,G,T"))); 		
		TestableCountProcessor t = new TestableCountProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" });
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		
		Map<String, Object> countResponse = t.runVariantCount(q);
		assertEquals(1,countResponse.get("count") );
	}

	@Test
	public void testVariantCountWithVariantInfoFiltersWhenFiltersDoNotMatchAnyVariants() throws Exception {
		TestableCountProcessor t = new TestableCountProcessor(true, new ArrayList<Set<String>>());

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" });
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();

		Map<String, Object> countResponse = t.runVariantCount(q);
		assertEquals("0",countResponse.get("count") );
	}

}
