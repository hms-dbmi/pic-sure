package edu.harvard.hms.dbmi.avillach;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;

import edu.harvard.dbmi.avillach.domain.QueryRequest;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import javax.ws.rs.core.Response;

import org.apache.commons.codec.Charsets;
import org.apache.commons.io.IOUtils;

/*
 * Note: All json is in /src/main/resources, see the convenience methods at the bottom of the class.
 * 
 * 		
		long randomNumber = ("query"+"random_string").hashCode();
		int zero_to_6 = (int) (randomNumber % 7);
		int adjustment = 3 - zero_to_6;
		
		Random rnd = new Random(randomNumber);
		int count = (int) (33 + (3-(7 * rnd.nextDouble())));

 */
public class AggregateDataSharingResourceRSAcceptanceTests {

	private ObjectMapper mapper = new ObjectMapper();

	private Pattern obfuscatedResultPattern = Pattern.compile("(\\d+) +-3");
	
	private ApplicationProperties mockProperties;

	private AggregateDataSharingResourceRS objectUnderTest;

	// Pick a random port between 20k and 52k 
	private final static int port = (int) ((Math.random()*32000)+20000);
	private final static String testURL = "http://localhost:"+port+"/";

	@Rule
	public WireMockClassRule wireMockRule = new WireMockClassRule(port);

	@Before
	public void setup() throws IOException {
		mockProperties = mock(ApplicationProperties.class);
		when(mockProperties.getTargetResourceId()).thenReturn("f0317fa9-0945-4390-993a-840416e97d13");
		when(mockProperties.getTargetPicsureObfuscationThreshold()).thenReturn("10");
		when(mockProperties.getTargetPicsureUrl()).thenReturn(testURL);
		when(mockProperties.getTargetPicsureToken()).thenReturn("This actually is not needed here, only for the proxy resource.");
		objectUnderTest = new AggregateDataSharingResourceRS(mockProperties);
		
		// Whenever the ADSRRS submits a search for "any" we return the contents of open_access_search_result.json
		wireMockRule.stubFor(post(urlEqualTo("/search"))
				.withRequestBody(equalToJson("{\"query\":\"any\"}"))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(getTestJson("open_access_search_result"))));

	}

	/**
	 * This is just a test to make sure equalToJson(...).match(...).isExactMatch() handles reordered JSON
	 * @throws IOException 
	 * @throws JsonProcessingException 
	 * @throws JsonMappingException 
	 */
	@Test
	public void testTest() throws JsonMappingException, JsonProcessingException, IOException {
		assertTrue(equalToJson("{\"a\":100,\"b\":\"foo\"}").match("{\"b\":\"foo\",\"a\":100}").isExactMatch());
	}

	@Test
	public void testNoObfuscationOnLargeCounts() throws IOException {
		expect_original_result_to_become_obfuscated_result("large_open_access_cross_count_result", "large_open_access_cross_count_result");
	}

	@Test
	public void testZeroNotObfuscated() throws IOException {
		expect_original_result_to_become_obfuscated_result("all_zero_open_access_cross_count_result", "all_zero_open_access_cross_count_result");
	}

	@Test
	public void testTenNotObfuscated() throws IOException {
		expect_original_result_to_become_obfuscated_result("all_ten_open_access_cross_count_result", "all_ten_open_access_cross_count_result");
	}

	@Test
	public void testOneThroughNineObfuscated() throws IOException {
		expect_original_result_to_become_obfuscated_result("all_less_ten_open_access_cross_count_result", "obfuscated_all_less_ten_open_access_cross_count_result");
	}

	@Test
	public void testSingleObfuscationPropagated() throws IOException {
		String obfuscated = getObfuscatedResponseForResult("one_obfuscated_open_access_cross_count_result");
		Map result = mapper.readValue(obfuscated, Map.class);
		
		// The parts of the result which don't need obfuscation should be unmodified
		String allC2Result = (String) result.remove("\\all\\c\\2\\");
		String allCResult = (String) result.remove("\\all\\c\\");
		String allResult = (String) result.remove("\\all\\");
		assertTrue(
				equalToJson(mapper.writeValueAsString(result))
				.match(getTestJson("one_obfuscated_open_access_cross_count_result_without_obfuscated_elements"))
				.isExactMatch());
		
		// \\all\\c\\2\\ should be "< 10"
		assertEquals(allC2Result, "< 10");

		// \\all\\c\\ should be some number between 60 and 66 with +-3 appended
		assertTrue(obfuscatedResultPattern.matcher(allCResult).matches());
		int allCNumericResult = getObfuscatedNumericResult(allCResult);
		assertTrue(allCNumericResult > 59 && allCNumericResult < 67);
		
		// \\all\\ should be some number between 300 and 336 with +-3 appended
		assertTrue(obfuscatedResultPattern.matcher(allResult).matches());
		int allNumericResult = getObfuscatedNumericResult(allResult);
		assertTrue(allNumericResult > 329 && allNumericResult < 337);
	}

	@Test
	public void testMultipleObfuscationPropagated() throws IOException {
		String obfuscated = getObfuscatedResponseForResult("two_obfuscated_open_access_cross_count_result");
		Map result = mapper.readValue(obfuscated, Map.class);
		
		// The parts of the result which don't need obfuscation should be unmodified
		String allC2Result = (String) result.remove("\\all\\c\\2\\");
		String allCResult = (String) result.remove("\\all\\c\\");
		String allD2Result = (String) result.remove("\\all\\d\\2\\");
		String allD3Result = (String) result.remove("\\all\\d\\3\\");
		String allDResult = (String) result.remove("\\all\\d\\");
		String allResult = (String) result.remove("\\all\\");
		assertTrue(
				equalToJson(mapper.writeValueAsString(result))
				.match(getTestJson("two_obfuscated_open_access_cross_count_result_without_obfuscated_elements"))
				.isExactMatch());
		
		// \\all\\c\\2\\, \\all\\d\\2\\, \\all\\d\\3\\ should be "< 10"
		assertEquals(allC2Result, "< 10");
		assertEquals(allD2Result, "< 10");
		assertEquals(allD3Result, "< 10");
		
		// \\all\\c\\ should be some number between 60 and 66 with +-3 appended
		assertTrue(obfuscatedResultPattern.matcher(allCResult).matches());
		int allCNumericResult = getObfuscatedNumericResult(allCResult);
		assertTrue(allCNumericResult > 59 && allCNumericResult < 67);
		
		// \\all\\d\\ should be some number between 52 and 58 with +-3 appended
		assertTrue(obfuscatedResultPattern.matcher(allCResult).matches());
		int allDNumericResult = getObfuscatedNumericResult(allDResult);
		assertTrue(allDNumericResult > 51 && allDNumericResult < 59);
		
		// \\all\\ should be some number between 300 and 336 with +-3 appended
		assertTrue(obfuscatedResultPattern.matcher(allResult).matches());
		int allNumericResult = getObfuscatedNumericResult(allResult);
		assertTrue(allNumericResult > 329 && allNumericResult < 337);
	}

	@Test
	public void testMiddleLessTenObfuscationPropagated() throws IOException {
		String obfuscated = getObfuscatedResponseForResult("middle_less_ten_obfuscated_open_access_cross_count_result");
		Map result = mapper.readValue(obfuscated, Map.class);
		
		// The parts of the result which don't need obfuscation should be unmodified
		String allC2Result = (String) result.remove("\\all\\c\\2\\");
		String allCResult = (String) result.remove("\\all\\c\\");
		String allD2Result = (String) result.remove("\\all\\d\\2\\");
		String allD3Result = (String) result.remove("\\all\\d\\3\\");
		String allDResult = (String) result.remove("\\all\\d\\");
		String allB1Result = (String) result.remove("\\all\\b\\1\\");
		String allB2Result = (String) result.remove("\\all\\b\\2\\");
		String allBResult = (String) result.remove("\\all\\b\\");
		String allResult = (String) result.remove("\\all\\");
		assertTrue(
				equalToJson(mapper.writeValueAsString(result))
				.match(getTestJson("middle_less_ten_obfuscated_open_access_cross_count_result_without_obfuscated_elements"))
				.isExactMatch());
		
		// \\all\\b\\1\\, \\all\\b\\2\\, \\all\\b\\, \\all\\c\\2\\, \\all\\d\\2\\, \\all\\d\\3\\ should be "< 10"
		assertEquals(allBResult, "< 10");
		assertEquals(allB1Result, "< 10");
		assertEquals(allB2Result, "< 10");
		assertEquals(allC2Result, "< 10");
		assertEquals(allD2Result, "< 10");
		assertEquals(allD3Result, "< 10");
		
		// \\all\\c\\ should be some number between 60 and 66 with +-3 appended
		assertTrue(obfuscatedResultPattern.matcher(allCResult).matches());
		int allCNumericResult = getObfuscatedNumericResult(allCResult);
		assertTrue(allCNumericResult > 59 && allCNumericResult < 67);
		
		// \\all\\d\\ should be some number between 52 and 58 with +-3 appended
		assertTrue(obfuscatedResultPattern.matcher(allCResult).matches());
		int allDNumericResult = getObfuscatedNumericResult(allDResult);
		assertTrue(allDNumericResult > 51 && allDNumericResult < 59);
		
		// \\all\\ should be some number between 300 and 336 with +-3 appended
		assertTrue(obfuscatedResultPattern.matcher(allResult).matches());
		int allNumericResult = getObfuscatedNumericResult(allResult);
		assertTrue(allNumericResult > 329 && allNumericResult < 337);
	}

	@Test
	public void testObfuscationDoesntChangeWithoutChangeInQuery() throws IOException {
		String obfuscated_1 = getObfuscatedResponseForResult("one_obfuscated_open_access_cross_count_result");
		String obfuscated_2 = getObfuscatedResponseForResult("one_obfuscated_open_access_cross_count_result");
		assertTrue(
				equalToJson(obfuscated_1)
				.match(obfuscated_2)
				.isExactMatch());
	}

	private QueryRequest getTestQuery() throws JsonProcessingException, JsonMappingException, IOException {
		return mapper.readValue(getTestJson("test_cross_count_query"), QueryRequest.class);
	}

	private String getObfuscatedTestQueryJson() throws JsonProcessingException, JsonMappingException, IOException {
		return getTestJson("obfuscated_cross_count_query");
	}

	private String getObfuscatedTestQueryJson2() throws JsonProcessingException, JsonMappingException, IOException {
		return getTestJson("obfuscated_cross_count_query2");
	}

	private String getTestJson(String json_file_name) throws IOException {
		return IOUtils.toString(this.getClass().getClassLoader().getResourceAsStream(json_file_name + ".json"), Charsets.UTF_8);
	}
	
	private int getObfuscatedNumericResult(String allC2Result) {
		return Integer.parseInt(obfuscatedResultPattern.matcher(allC2Result).group(1));
	}

	private void expect_original_result_to_become_obfuscated_result(String originalResult, String obfuscatedResult)
			throws JsonProcessingException, JsonMappingException, IOException {
		String responseJson = getObfuscatedResponseForResult(originalResult);
		assertTrue(
				equalToJson(responseJson)
				.match(getTestJson(obfuscatedResult))
				.isExactMatch());
	}

	private String getObfuscatedResponseForResult(String originalResult)
			throws JsonProcessingException, JsonMappingException, IOException {
		wireMockRule.stubFor(post(urlEqualTo("/query/sync"))
				.withRequestBody(equalToJson(mapper.writeValueAsString(getTestQuery())))
				.withHeader("Authorization", equalTo("Bearer This actually is not needed here, only for the proxy resource."))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(getTestJson(originalResult))));
		
		Response response = objectUnderTest.querySync(getTestQuery());
		// TODO: This is what should be sent
//		Response response = objectUnderTest.querySync(mapper.readValue(getObfuscatedTestQueryJson(), QueryRequest.class));

		String responseJson = (String) response.getEntity();
		return responseJson;
	}

}
