package edu.harvard.hms.dbmi.avillach.auth.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

public class JsonUtilsTest {

	
	/**
	 * {"queryTemplate":"{
	 * \"categoryFilters\":{\"\\\\demographics\\\\SEX\\\\\":[\"male\"]},
	 * \"numericFilters\":{\"\\\\demographics\\\\AGE\\\\\":{\"max\":\"50\"}},
	 * \"requiredFields\":[\"\\\\_Study Accession with Patient ID\\\\\"],
	 * \"anyRecordOf\":[],
	 * \"variantInfoFilters\":[
	 * 		{\"categoryVariantInfoFilters\":{},
	 * 		\"numericVariantInfoFilters\":{}}],
	 * \"expectedResultType\":[\"COUNT\"]}"
	 * }
	 */
	
    @Before
    public void init() {
    }

    @Test
    public void testmergeTemplateMap() throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
    	//template with one category filter and one required field
    	String template1Str = "{\"numericFilters\":{},\"categoryFilters\":{\"\\\\demographics\\\\SEX\\\\\":[\"male\"]},\"requiredFields\":[\"\\\\_Study Accession with Patient ID\\\\\"],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template1 = objectMapper.readValue(template1Str, Map.class);

    	//template with one numeric filter and one required field
    	String template2Str = "{\"categoryFilters\":{},\"numericFilters\":{\"\\\\demographics\\\\AGE\\\\\":{\"max\":\"50\"}},\"requiredFields\":[\"\\\\_Study Accession with Patient ID\\\\\"],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template2 = objectMapper.readValue(template2Str, Map.class);
    	
    	Map mergedTemplate = JsonUtils.mergeTemplateMap(template1, template2);
    	
    	assertNotNull(mergedTemplate.get("categoryFilters"));
    	
    	assertNotNull(mergedTemplate.get("numericFilters"));
    	
    	assertNotNull(mergedTemplate.get("anyRecordOf"));
    	
    	assertNotNull(mergedTemplate.get("requiredFields"));
    	
    	//both required fields (same) should have been merged
    	assertTrue(mergedTemplate.get("requiredFields") instanceof Collection);
    	assertEquals(1, ((Collection)mergedTemplate.get("requiredFields")).size());
    	
    	assertNotNull(mergedTemplate.get("variantInfoFilters"));
    	
    	assertNotNull(mergedTemplate.get("expectedResultType"));
    }
    
    /**
     * Make sure we don't fail is one parameter is empty
     * @throws JsonParseException
     * @throws JsonMappingException
     * @throws IOException
     */
    @Test
    public void testmergeTemplateMapEmptyMap() throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
    	//template with one category filter and one required field
    	String template1Str = "{\"numericFilters\":{},\"categoryFilters\":{\"\\\\demographics\\\\SEX\\\\\":[\"male\"]},\"requiredFields\":[\"\\\\_Study Accession with Patient ID\\\\\"],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template1 = objectMapper.readValue(template1Str, Map.class);
    	
    	//template with one numeric filter and one required field
    	String template2Str = "{}";
    	Map template2 = objectMapper.readValue(template2Str, Map.class);
    	
    	Map mergedTemplate = JsonUtils.mergeTemplateMap(template1, template2);
    	
    	assertNotNull(mergedTemplate.get("categoryFilters"));
    	
    	assertNotNull(mergedTemplate.get("numericFilters"));
    	
    	assertNotNull(mergedTemplate.get("anyRecordOf"));
    	
    	assertNotNull(mergedTemplate.get("requiredFields"));
    	
    	//both required fields (same) should have been merged
    	assertTrue(mergedTemplate.get("requiredFields") instanceof Collection);
    	assertEquals(1, ((Collection)mergedTemplate.get("requiredFields")).size());
    	
    	assertNotNull(mergedTemplate.get("variantInfoFilters"));
    	
    	assertNotNull(mergedTemplate.get("expectedResultType"));
    }
    
    /**
     * Make sure we don't fail is one parameter is empty
     * @throws JsonParseException
     * @throws JsonMappingException
     * @throws IOException
     */
    @Test
    public void testmergeTemplateMapEmptyMapInverse() throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
    	//template with one category filter and one required field
    	String template1Str = "{\"numericFilters\":{},\"categoryFilters\":{\"\\\\demographics\\\\SEX\\\\\":[\"male\"]},\"requiredFields\":[\"\\\\_Study Accession with Patient ID\\\\\"],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template1 = objectMapper.readValue(template1Str, Map.class);
    	
    	//empty object
    	String template2Str = "{}";
    	Map template2 = objectMapper.readValue(template2Str, Map.class);
    	
    	//Make sure this works for both parameters
    	Map mergedTemplate = JsonUtils.mergeTemplateMap(template2, template1);
    	
    	assertNotNull(mergedTemplate.get("categoryFilters"));
    	
    	assertNotNull(mergedTemplate.get("numericFilters"));
    	
    	assertNotNull(mergedTemplate.get("anyRecordOf"));
    	
    	assertNotNull(mergedTemplate.get("requiredFields"));
    	
    	//both required fields (same) should have been merged
    	assertTrue(mergedTemplate.get("requiredFields") instanceof Collection);
    	assertEquals(1, ((Collection)mergedTemplate.get("requiredFields")).size());
    	
    	assertNotNull(mergedTemplate.get("variantInfoFilters"));
    	
    	assertNotNull(mergedTemplate.get("expectedResultType"));
    }
    
    @Test
    public void testmergeTemplateMapMergeFilters() throws IOException{
		ObjectMapper objectMapper = new ObjectMapper();
    	//template with one category filter and one required field
    	String template1Str = "{\"numericFilters\":{},\"categoryFilters\":{\"\\\\demographics\\\\SEX\\\\\":[\"male\"]},\"requiredFields\":[\"\\\\_Study Accession with Patient ID\\\\\"],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template1 = objectMapper.readValue(template1Str, Map.class);
    	
    	//template with one category filter (different value) and one required field
    	String template2Str = "{\"numericFilters\":{},\"categoryFilters\":{\"\\\\demographics\\\\SEX\\\\\":[\"female\"]},\"requiredFields\":[\"\\\\_Study Accession with Patient ID\\\\\"],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template2 = objectMapper.readValue(template2Str, Map.class);
    	
    	Map mergedTemplate = JsonUtils.mergeTemplateMap(template1, template2);
    	
    	//validate that the same category filters are merged
    	assertNotNull(mergedTemplate.get("categoryFilters"));
    	assertTrue(mergedTemplate.get("categoryFilters") instanceof Map);
    	Map categoryFilters = (Map)mergedTemplate.get("categoryFilters");
    	assertEquals(1, categoryFilters.size());
    	
    	//less escaping needed here for some reason
    	assertNotNull(categoryFilters.get("\\demographics\\SEX\\"));
    	assertTrue(categoryFilters.get("\\demographics\\SEX\\") instanceof Collection);
    	assertEquals(2, ((Collection)categoryFilters.get("\\demographics\\SEX\\")).size());
    	
    	assertNotNull(mergedTemplate.get("numericFilters"));
    	
    	assertNotNull(mergedTemplate.get("anyRecordOf"));
    	
    	assertNotNull(mergedTemplate.get("requiredFields"));
    	
    	//both required fields (same) should have been merged
    	assertTrue(mergedTemplate.get("requiredFields") instanceof Collection);
    	assertEquals(1, ((Collection)mergedTemplate.get("requiredFields")).size());
    	
    	assertNotNull(mergedTemplate.get("variantInfoFilters"));
    	
    	assertNotNull(mergedTemplate.get("expectedResultType"));
    }
    
    @Test
    public void testmergeTemplateMapMultipleNumericFilters() throws IOException{
		ObjectMapper objectMapper = new ObjectMapper();
    	//template with one category filter and one required field
    	String template1Str = "{\"categoryFilters\":{},\"numericFilters\":{\"\\\\demographics\\\\AGE\\\\\":{\"min\":\"20\"}},\"requiredFields\":[\"\\\\_Study Accession with Patient ID\\\\\"],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template1 = objectMapper.readValue(template1Str, Map.class);
    	
    	//template with one numeric filter and one required field
    	String template2Str = "{\"categoryFilters\":{},\"numericFilters\":{\"\\\\demographics\\\\AGE\\\\\":{\"max\":\"50\"}},\"requiredFields\":[\"\\\\_Study Accession with Patient ID\\\\\"],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template2 = objectMapper.readValue(template2Str, Map.class);
    	
    	Map mergedTemplate = JsonUtils.mergeTemplateMap(template1, template2);
    	
    	assertNotNull(mergedTemplate.get("categoryFilters"));
    	
    	assertNotNull(mergedTemplate.get("numericFilters"));
    	assertTrue(mergedTemplate.get("numericFilters") instanceof Map);
    	Map categoryFilters = (Map)mergedTemplate.get("numericFilters");
    	assertEquals(1, categoryFilters.size());
    	
    	//less escaping needed here for some reason
    	assertNotNull(categoryFilters.get("\\demographics\\AGE\\"));
    	assertTrue(categoryFilters.get("\\demographics\\AGE\\") instanceof Map);
    	assertEquals(2, ((Map)categoryFilters.get("\\demographics\\AGE\\")).size());
    	//values are all stored as strings, since json/js doesn't care
    	assertEquals("50", ((Map)categoryFilters.get("\\demographics\\AGE\\")).get("max"));
		assertEquals("20", ((Map)categoryFilters.get("\\demographics\\AGE\\")).get("min"));
    	
    	
    	assertNotNull(mergedTemplate.get("anyRecordOf"));
    	
    	assertNotNull(mergedTemplate.get("requiredFields"));
    	
    	//both required fields (same) should have been merged
    	assertTrue(mergedTemplate.get("requiredFields") instanceof Collection);
    	assertEquals(1, ((Collection)mergedTemplate.get("requiredFields")).size());
    	
    	assertNotNull(mergedTemplate.get("variantInfoFilters"));
    	
    	assertNotNull(mergedTemplate.get("expectedResultType"));
    }
    
    
    @Test
    public void testmergeTemplateMapVariantInfo() throws IOException{
		ObjectMapper objectMapper = new ObjectMapper();
    	//template with one category filter and one required field
    	String template1Str = "{\"numericFilters\":{},\"categoryFilters\":{},\"requiredFields\":[],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{\"variant_severity\":[\"HIGH\"]},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template1 = objectMapper.readValue(template1Str, Map.class);
    	
    	//template with one numeric filter and one required field
    	String template2Str = "{\"categoryFilters\":{},\"numericFilters\":{},\"requiredFields\":[],\"anyRecordOf\":[],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{\"variant_severity\":[\"LOW\"]},\"numericVariantInfoFilters\":{}}],\"expectedResultType\":\"COUNT\"}";
    	Map template2 = objectMapper.readValue(template2Str, Map.class);
    	
    	Map mergedTemplate = JsonUtils.mergeTemplateMap(template1, template2);
    	
    	assertNotNull(mergedTemplate.get("categoryFilters"));
    	
    	assertNotNull(mergedTemplate.get("numericFilters"));
    	
    	assertNotNull(mergedTemplate.get("anyRecordOf"));
    	
    	assertNotNull(mergedTemplate.get("requiredFields"));
    	
    	//NO required fields in this test
    	assertTrue(mergedTemplate.get("requiredFields") instanceof Collection);
    	assertEquals(0, ((Collection)mergedTemplate.get("requiredFields")).size());
    	
    	assertNotNull(mergedTemplate.get("variantInfoFilters"));
    	assertTrue(mergedTemplate.get("variantInfoFilters") instanceof Collection);
    	Collection variantInfoFilters = (Collection)mergedTemplate.get("variantInfoFilters");

    	assertNotNull(mergedTemplate.get("expectedResultType"));
    }
}
