package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CountProcessorTest {

	private final CountProcessor countProcessor;

	private final AbstractProcessor mockAbstractProcessor;

	public CountProcessorTest(@Mock AbstractProcessor mockAbstractProcessor) {
		this.mockAbstractProcessor = mockAbstractProcessor;
		this.countProcessor = new CountProcessor(mockAbstractProcessor);
	}

	@Test
	public void testVariantCountWithEmptyQuery() {
		Map<String, Object> countResponse = countProcessor.runVariantCount(new Query());
		assertEquals("0",countResponse.get("count") );
	}

	@Test
	public void testVariantCountWithEmptyVariantInfoFiltersInQuery() {
		Query query = new Query();
		query.setVariantInfoFilters(new ArrayList<>());
		Map<String, Object> countResponse = countProcessor.runVariantCount(query);
		assertEquals("0",countResponse.get("count") );
	}

	@Test
	public void testVariantCountReturningVariants() throws IOException {
		Query query = new Query();
		query.setVariantInfoFilters(List.of(new Query.VariantInfoFilter()));

		when(mockAbstractProcessor.getVariantList(query)).thenReturn(List.of("variant1", "variant2"));
		Map<String, Object> countResponse = countProcessor.runVariantCount(query);
		assertEquals(2,countResponse.get("count") );
	}

	// todo: test these directly in AbstractProcessor
	/*
	@Test
	public void testVariantCountWithVariantInfoFiltersWithMultipleVariantsWithIntersectingKeys() throws Exception {
		ArrayList<Set<Integer>> data = new ArrayList<>(List.of(
				Set.of(1),
				Set.of(1, 2)));
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
		List<ArrayList<Set<Integer>>> data1 = new ArrayList<ArrayList<Set<Integer>>>(new ArrayList(List.of(
				new ArrayList(List.of(Set.of(1, 2))),new ArrayList(List.of(Set.of(1, 3))))));
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
		ArrayList<Set<Integer>> data = new ArrayList(List.of(
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
		TestableCountProcessor t = new TestableCountProcessor(true, new ArrayList<Set<Integer>>());

		Map<String, String[]> categoryVariantInfoFilters = Map.of("FILTERKEY", new String[] { "test1" });
		VariantInfoFilter variantInfoFilter = new VariantInfoFilter();
		variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

		List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
		variantInfoFilters.add(variantInfoFilter);
		Query q = new Query();

		Map<String, Object> countResponse = t.runVariantCount(q);
		assertEquals("0",countResponse.get("count") );
	}*/

}
