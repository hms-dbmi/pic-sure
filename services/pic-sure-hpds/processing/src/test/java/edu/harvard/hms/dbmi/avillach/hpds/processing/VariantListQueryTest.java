package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class VariantListQueryTest {

	@Mock
	private AbstractProcessor mockAbstractProcessor;

	static {
		System.setProperty("VCF_EXCERPT_ENABLED", "TRUE");
	}
	
	public class TestableVariantListProcessor extends VariantListProcessor {
		private List<ArrayList<Set<Integer>>> testVariantSets;
		private int callCount = 0;
		
		
		public TestableVariantListProcessor(boolean isOnlyForTests, ArrayList<Set<Integer>> testVariantSets)
				throws ClassNotFoundException, FileNotFoundException, IOException {
			super(isOnlyForTests, mockAbstractProcessor);
		}

		/*public TestableVariantListProcessor(boolean isOnlyForTests, List<ArrayList<Set<Integer>>> testVariantSets)
				throws ClassNotFoundException, FileNotFoundException, IOException {
			super(isOnlyForTests);
			this.testVariantSets = testVariantSets;
			//we still need an object to reference when checking the variant store, even if it's empty.
			variantStore = new VariantStore();
			variantStore.setPatientIds(new String[0]);
			allIds = new TreeSet<>(Set.of(10001,20002));
		}*/

		public void addVariantsMatchingFilters(VariantInfoFilter filter, ArrayList<Set<Integer>> variantSets) {
			for (Set<Integer> set : testVariantSets.get(callCount++ % testVariantSets.size())) {
				System.out.println("Adding " + Arrays.deepToString(set.toArray()));
				variantSets.add(set);
			}
		}

	}

	@Test
	public void testVariantListWithEmptyQuery() throws Exception {
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, new ArrayList<Set<Integer>>());
		assertEquals("[]", t.runVariantListQuery(new Query()));
	} 
	
	@Test
	public void testVariantListWithNullVariantInfoFiltersInQuery() throws Exception {
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, new ArrayList<Set<Integer>>());
		Query query = new Query();
		query.setVariantInfoFilters(null);
		assertEquals("[]", t.runVariantListQuery(query));
	}	
	
	@Test
	public void testVariantListWithVariantInfoFiltersWithMultipleVariantsButNoIntersectingKeys() throws Exception {
		ArrayList<Set<Integer>> data = new ArrayList<>(List.of(
				Set.of(42),
				Set.of(99)));
		
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = 
				Map.of("FILTERKEY", new String[] {"test1"});
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = List.of(variantInfoFilter);

		Query q = new Query();
		q.setVariantInfoFilters(variantInfoFilters);
		assertEquals("[]", t.runVariantListQuery(q));
	}	

	public void testVariantListWithVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		ArrayList<Set<Integer>> data = new ArrayList<>(List.of(
				Set.of(42),
				Set.of(42, 99)));

		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.setVariantInfoFilters(variantInfoFilters);
		String runVariantListQuery = t.runVariantListQuery(q);
		assertEquals("[2,1234,G,T]", runVariantListQuery);
	}	
	
	/*@Test
	public void testVariantListWithTwoVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		List<ArrayList<Set<Integer>>> data = new ArrayList<ArrayList<Set<Integer>>>(new ArrayList(
				List.of(new ArrayList(List.of(Set.of(42, 99))),
						new ArrayList(List.of(Set.of(42, 999))))));
		
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
	}*/

	public void testVariantListWithVariantInfoFiltersWithOnlyOneFilterCriteria() throws Exception {
		ArrayList<Set<Integer>> data = new ArrayList<Set<Integer>>(List.of(
				Set.of(42)));
		
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, data);

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.setVariantInfoFilters(variantInfoFilters);
		String runVariantListQuery = t.runVariantListQuery(q);
		assertEquals("[2,1234,G,T]", runVariantListQuery);
	}
	
	@Test
	public void testVariantListtWithVariantInfoFiltersWhenFiltersDoNotMatchAnyVariants() throws Exception {
		TestableVariantListProcessor t = new TestableVariantListProcessor(true, new ArrayList<Set<Integer>>());

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" }); 
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();
		q.setVariantInfoFilters(variantInfoFilters);
		String runVariantListQuery = t.runVariantListQuery(q);
		assertEquals("[]", runVariantListQuery);
	}	

}
