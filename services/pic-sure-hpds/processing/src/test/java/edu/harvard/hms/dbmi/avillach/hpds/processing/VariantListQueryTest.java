package edu.harvard.hms.dbmi.avillach.hpds.processing;

import static org.junit.Assert.assertEquals;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;

public class VariantListQueryTest {

	public class TestableVariantListProcessor extends VariantListProcessor {
		private List<ArrayList<Set<String>>> testVariantSets;
		private int callCount = 0;
		
		
		public TestableVariantListProcessor(boolean isOnlyForTests, ArrayList<Set<String>> testVariantSets)
				throws ClassNotFoundException, FileNotFoundException, IOException {
			super(isOnlyForTests);
			this.testVariantSets = List.of(testVariantSets);
		}

		public TestableVariantListProcessor(boolean isOnlyForTests, List<ArrayList<Set<String>>> testVariantSets)
				throws ClassNotFoundException, FileNotFoundException, IOException {
			super(isOnlyForTests);
			this.testVariantSets = testVariantSets;
		}

		public void addVariantsMatchingFilters(VariantInfoFilter filter, ArrayList<Set<String>> variantSets) {
			for (Set<String> set : testVariantSets.get(callCount++)) {
				variantSets.add(set);
			}
		}

	}

	@Test
	public void testVariantListWithEmptyQuery() throws Exception {
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, new ArrayList<Set<String>>());
		assertEquals("", t.runVariantListQuery(new Query()));
	} 
	
	@Test
	public void testVariantListWithNullVariantInfoFiltersInQuery() throws Exception {
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, new ArrayList<Set<String>>());
		Query query = new Query();
		query.variantInfoFilters = null;
		assertEquals("", t.runVariantListQuery(query));
	}	
	
	@Test
	public void testVariantListWithVariantInfoFiltersWithMultipleVariantsButNoIntersectingKeys() throws Exception {
		ArrayList<Set<String>> data = new ArrayList<Set<String>>(List.of(
				Set.of("key1"), 
				Set.of("key2")));

		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = 
				Map.of("key1", new String[] {"test1"});
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
				Set.of("key1"), 
				Set.of("key1","key2")));		

		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("key1", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		String runVariantListQuery = t.runVariantListQuery(q);
		assertEquals("[key1]", runVariantListQuery);
	}	
	
	@Test
	public void testVariantListWithTwoVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		List<ArrayList<Set<String>>> data = new ArrayList<ArrayList<Set<String>>>(new ArrayList(
				List.of(new ArrayList(List.of(Set.of("key1", "key3"))),
						new ArrayList(List.of(Set.of("key1", "key2"))))));
		
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);
		
		Map<String, String[]> categoryVariantInfoFilters = Map.of("key1", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		VariantInfoFilter variantInfoFilter2 = new VariantInfoFilter();
		variantInfoFilter2.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>(
				List.of(variantInfoFilter, variantInfoFilter2));
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		String runVariantListQuery = t.runVariantListQuery(q);
		assertEquals("[key1, key2, key3]", runVariantListQuery);
	}
	
	@Test
	public void testVariantListWithVariantInfoFiltersWithOnlyOneFilterCriteria() throws Exception {
		ArrayList<Set<String>> data = new ArrayList<Set<String>>(List.of(
				Set.of("key1")));
		
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("key1", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		String runVariantListQuery = t.runVariantListQuery(q);
		assertEquals("[key1]", runVariantListQuery);
	}
	
	@Test
	public void testVariantListtWithVariantInfoFiltersWhenFiltersDoNotMatchAnyVariants() throws Exception {
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, new ArrayList<Set<String>>());

		Map<String, String[]> categoryVariantInfoFilters = Map.of("key1", new String[] { "test1" }); 
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
