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

public class CountProcessorTest {

	public class TestableCountProcessor extends CountProcessor {
		private List<ArrayList<Set<String>>> testVariantSets;
		private int callCount = 0;
		
		
		public TestableCountProcessor(boolean isOnlyForTests, ArrayList<Set<String>> testVariantSets)
				throws ClassNotFoundException, FileNotFoundException, IOException {
			super(isOnlyForTests);
			this.testVariantSets = List.of(testVariantSets);
		}

		public TestableCountProcessor(boolean isOnlyForTests, List<ArrayList<Set<String>>> testVariantSets)
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
	public void testVariantCountWithEmptyQuery() throws Exception {
		TestableCountProcessor t = new TestableCountProcessor(true, new ArrayList<Set<String>>());
		assertEquals(0, t.runVariantCount(new Query()));
	}

	@Test
	public void testVariantCountWithEmptyVariantInfoFiltersInQuery() throws Exception {
		TestableCountProcessor t = new TestableCountProcessor(true, new ArrayList<Set<String>>());
		Query query = new Query();
		query.variantInfoFilters = new ArrayList<>();
		assertEquals(0, t.runVariantCount(query));
	}

	@Test
	public void testVariantCountWithVariantInfoFiltersWithMultipleVariantsButNoIntersectionKeys() throws Exception {
		ArrayList<Set<String>> data = new ArrayList<Set<String>>(List.of(
				Set.of("key1"), 
				Set.of("key2")));

		TestableCountProcessor t = new TestableCountProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = 
				Map.of("key1", new String[] {"test1"});
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = List.of(variantInfoFilter);

		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		assertEquals(0, t.runVariantCount(q));
	}

	@Test
	public void testVariantCountWithVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		ArrayList<Set<String>> data = new ArrayList<Set<String>>(List.of(
				Set.of("key1"),
				Set.of("key1","key2"))); 
		TestableCountProcessor t = new TestableCountProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("key1", new String[] { "test1" });
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		assertEquals(1, t.runVariantCount(q));
	}

	@Test
	public void testVariantCountWithTwoVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		List<ArrayList<Set<String>>> data1 = new ArrayList<ArrayList<Set<String>>>(new ArrayList(List.of(
				new ArrayList(List.of(Set.of("key1", "key3"))),new ArrayList(List.of(Set.of("key1", "key2"))))));
		TestableCountProcessor t = new TestableCountProcessor(true, data1);
		
		Map<String, String[]> categoryVariantInfoFilters = Map.of("key1", new String[] { "test1" });
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		VariantInfoFilter variantInfoFilter2 = new VariantInfoFilter();
		variantInfoFilter2.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>(
				List.of(variantInfoFilter, variantInfoFilter2));
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		assertEquals(3, t.runVariantCount(q));
	}

	@Test
	public void testVariantCountWithVariantInfoFiltersWithOnlyOneFilterCriteria() throws Exception {
		ArrayList<Set<String>> data = new ArrayList(List.of(
				Set.of("key1"))); 		
		TestableCountProcessor t = new TestableCountProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("key1", new String[] { "test1" });
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		assertEquals(1, t.runVariantCount(q));
	}

	@Test
	public void testVariantCountWithVariantInfoFiltersWhenFiltersDoNotMatchAnyVariants() throws Exception {
		TestableCountProcessor t = new TestableCountProcessor(true, new ArrayList<Set<String>>());

		Map<String, String[]> categoryVariantInfoFilters = Map.of("key1", new String[] { "test1" });
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.variantInfoFilters = variantInfoFilters;
		assertEquals(0, t.runVariantCount(q));
	}

}
