package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.Filter;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.Query;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.ResultType;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DataServiceTests {

    static DataProcessingService dataProcessingService;

    @BeforeAll
    static void setUp() {
        dataProcessingService = new DataProcessingService();
    }

    @Nested
    class CategoricalTests {
        @Test
        @DisplayName("Var name is the title")
        void TestSingleSmallCategoryHasCorrectResults() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.requiredFields.add("\\phs000209\\pht001121\\phv00087119\\asthmaf\\");
            query.expectedResultType = ResultType.COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap = new TreeMap<>();
            variableMap.put("YES", 100);
            variableMap.put("NO", 251);
            variableMap.put("I DON'T KNOW", 3);
            crossCountsMap.put("\\phs000209\\pht001121\\phv00087119\\asthmaf\\", variableMap);

            List<CategoricalData> list = dataProcessingService.getCategoricalData(crossCountsMap);
            Map<String, Integer> varMap = list.get(0).getCategoricalMap();
            assertTrue(list.size()>0);
            assertEquals("Variable distribution of phv00087119: asthmaf", list.get(0).getTitle());
            assertEquals("asthmaf", list.get(0).getXAxisName());
            assertEquals("Number of Participants", list.get(0).getYAxisName());
            assertEquals(3, varMap.size());
            assertEquals(100, varMap.get("YES"));
            assertEquals(251, varMap.get("NO"));
            assertEquals(3, varMap.get("I DON'T KNOW"));
            int count = 0;
            // Expected Order: NO, YES, I DON'T KNOW (by value size)
            List<String> keys = Arrays.asList("NO", "YES", "I DON'T KNOW");
            for (Map.Entry<String, Integer> entry : list.get(0).getCategoricalMap().entrySet()) {
                String key = keys.get(count);
                assertEquals(key, entry.getKey());
                count++;
            }
        }

        @Test
        @DisplayName("Correct order and other bar is last")
        void TestSingleRequiredLargeCategoryHasCorrectOrder() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.requiredFields.add("\\DCC Harmonized data set\\demographic\\subcohort_1\\");
            query.expectedResultType = ResultType.COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap = new TreeMap<>();
            variableMap.put("MESA Classic Cohort", 6543); //4
            variableMap.put("WHI Clinical Trial", 65030); //2
            variableMap.put("FHS Thrid Generation Cohort", 4156); //7
            variableMap.put("CARDIA: No Subcohort Structure", 3781);
            variableMap.put("FHS Offspring Cohort", 5030); //5
            variableMap.put("GENOA African-American Cohort", 1899);
            variableMap.put("COPDGene: No Subcohort Structure", 11234); //3
            variableMap.put("CHS Original Cohort", 4540); // 6
            variableMap.put("JHS: No Subcohort Structure", 3581);
            variableMap.put("SAS: No Subcohort Structure", 3181);
            variableMap.put("WHI Observational Study", 80922); //1
            crossCountsMap.put("\\DCC Harmonized data set\\demographic\\subcohort_1\\", variableMap);

            List<CategoricalData> list = dataProcessingService.getCategoricalData(crossCountsMap);
            Map<String, Integer> varMap = list.get(0).getCategoricalMap();
            assertTrue(list.size()>0);
            assertEquals("Variable distribution of demographic: subcohort_1", list.get(0).getTitle());
            assertEquals("subcohort_1", list.get(0).getXAxisName());
            assertEquals("Number of Participants", list.get(0).getYAxisName());
            assertEquals(8, varMap.size());
            assertEquals(6543, varMap.get("MESA Classic Cohort"));
            assertEquals(12442, varMap.get("Other"));
            Map<String, Integer> lastItemMap = varMap.entrySet().stream().skip(varMap.size()-1).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertEquals(1, lastItemMap.size());
            assertTrue(lastItemMap.containsKey("Other"));
            assertEquals(12442, lastItemMap.get("Other"));

            String[] keys = new String[]{"WHI Observational Study", "WHI Clinical Trial", "COPDGene: No Subcohort Structure", "MESA Classic Cohort", "FHS Offspring Cohort", "CHS Original Cohort", "FHS Thrid Generation Cohort", "Other"};
            int count = 0;
            for (Map.Entry<String, Integer> entry : list.get(0).getCategoricalMap().entrySet()) {
                assertEquals(keys[count], entry.getKey());
                count++;
            }
        }

        @Test
        @DisplayName("Ensure large category has other bar and correct count")
        void TestSingleRequiredLargeCategoryHasCorrectResults() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.requiredFields.add("\\DCC Harmonized data set\\demographic\\subcohort_1\\");
            query.expectedResultType = ResultType.COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap = new TreeMap<>();
            variableMap.put("MESA Classic Cohort", 6543); //4
            variableMap.put("WHI Clinical Trial", 65030); //2
            variableMap.put("FHS Thrid Generation Cohort", 4156); //7
            variableMap.put("CARDIA: No Subcohort Structure", 3781);
            variableMap.put("FHS Offspring Cohort", 5030); //5
            variableMap.put("GENOA African-American Cohort", 1899);
            variableMap.put("COPDGene: No Subcohort Structure", 11234); //3
            variableMap.put("CHS Original Cohort", 4540); // 6
            variableMap.put("JHS: No Subcohort Structure", 3581);
            variableMap.put("SAS: No Subcohort Structure", 3181);
            variableMap.put("WHI Observational Study", 80922); //1
            crossCountsMap.put("\\DCC Harmonized data set\\demographic\\subcohort_1\\", variableMap);

            List<CategoricalData> list = dataProcessingService.getCategoricalData(crossCountsMap);
            Map<String, Integer> varMap = list.get(0).getCategoricalMap();
            assertTrue(list.size()>0);
            assertEquals("Variable distribution of demographic: subcohort_1", list.get(0).getTitle());
            assertEquals("subcohort_1", list.get(0).getXAxisName());
            assertEquals("Number of Participants", list.get(0).getYAxisName());
            assertEquals(8, varMap.size());
            assertEquals(6543, varMap.get("MESA Classic Cohort"));
            assertEquals(12442, varMap.get("Other"));
            Map<String, Integer> lastItemMap = varMap.entrySet().stream().skip(varMap.size()-1).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertEquals(1, lastItemMap.size());
            assertTrue(lastItemMap.containsKey("Other"));
            assertEquals(12442, lastItemMap.get("Other"));
        }

        @Test
        @DisplayName("Test Other bar NOT there for 8 categories")
        void TestSingleRequired8CategoryWithCorrectResults() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.requiredFields.add("\\DCC Harmonized data set\\demographic\\subcohort_1\\");
            query.expectedResultType = ResultType.COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap = new TreeMap<>();
            variableMap.put("MESA Classic Cohort", 6543);
            variableMap.put("WHI Clinical Trial", 65030);
            variableMap.put("FHS Thrid Generation Cohort", 4156);
            variableMap.put("CARDIA: No Subcohort Structure", 3781);
            variableMap.put("FHS Offspring Cohort", 5030);
            variableMap.put("COPDGene: No Subcohort Structure", 11234);
            variableMap.put("CHS Original Cohort", 4540);
            variableMap.put("WHI Observational Study", 80922);
            crossCountsMap.put("\\DCC Harmonized data set\\demographic\\subcohort_1\\", variableMap);

            List<CategoricalData> list = dataProcessingService.getCategoricalData(crossCountsMap);
            Map<String, Integer> varMap = list.get(0).getCategoricalMap();
            assertTrue(list.size()>0);
            assertEquals("Variable distribution of demographic: subcohort_1", list.get(0).getTitle());
            assertEquals("subcohort_1", list.get(0).getXAxisName());
            assertEquals("Number of Participants", list.get(0).getYAxisName());
            assertEquals(8, varMap.size());
            assertFalse(varMap.containsKey("Other"));
            Map<String, Integer> lastItemMap = varMap.entrySet().stream().skip(7).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertEquals(1, lastItemMap.size());
            assertFalse(lastItemMap.containsKey("Other"));
        }

        @Test
        @DisplayName("Test Many filters")
        void TestManyRequiredCategoricalFilters() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.requiredFields.add("\\phs000209\\pht001121\\phv00087119\\asthmaf\\");
            query.requiredFields.add("\\phs000209\\pht001121\\phv00087301\\alcfc\\");
            query.requiredFields.add("\\phs000209\\pht001121\\phv00087319\\benzodfc\\");
            query.expectedResultType = ResultType.COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap1 = new TreeMap<>();
            variableMap1.put("YES", 100);
            variableMap1.put("NO", 251);
            variableMap1.put("I DON'T KNOW", 3);
            Map<String, Integer> variableMap2 = new TreeMap<>();
            variableMap2.put("FORMER", 434);
            variableMap2.put("CURRENT", 760);
            variableMap2.put("NEVER", 321);
            Map<String, Integer> variableMap3 = new TreeMap<>();
            variableMap3.put("YES", 1500);
            variableMap3.put("NO", 40);
            crossCountsMap.put("\\phs000209\\pht001121\\phv00087119\\asthmaf\\", variableMap1);
            crossCountsMap.put("\\phs000209\\pht001121\\phv00087301\\alcfc\\", variableMap2);
            crossCountsMap.put(("\\phs000209\\pht001121\\phv00087319\\benzodfc\\"), variableMap3);

            List<CategoricalData> list = dataProcessingService.getCategoricalData(crossCountsMap);
            assertEquals(crossCountsMap.size(), list.size());
            Map<String, Integer> varMap1 = list.get(0).getCategoricalMap();
            Map<String, Integer> varMap2 = list.get(1).getCategoricalMap();
            Map<String, Integer> varMap3 = list.get(2).getCategoricalMap();
            assertEquals(variableMap1.size(), varMap1.size());
            assertEquals(variableMap2.size(), varMap2.size());
            assertEquals(variableMap3.size(), varMap3.size());
            assertEquals("Variable distribution of phv00087119: asthmaf", list.get(0).getTitle());
            assertEquals("Variable distribution of phv00087301: alcfc", list.get(1).getTitle());
            assertEquals("Variable distribution of phv00087319: benzodfc", list.get(2).getTitle());
            assertEquals("asthmaf", list.get(0).getXAxisName());
            assertEquals("alcfc", list.get(1).getXAxisName());
            assertEquals("benzodfc", list.get(2).getXAxisName());
            assertEquals("Number of Participants", list.get(0).getYAxisName());
            assertEquals("Number of Participants", list.get(1).getYAxisName());
            assertEquals("Number of Participants", list.get(2).getYAxisName());

            for (int i = 0; i < varMap1.size(); i++) {
                assertEquals(variableMap1.get(varMap1.keySet().toArray()[i]), varMap1.values().toArray()[i]);
            }
            for (int i = 0; i < varMap2.size(); i++) {
                assertEquals(variableMap2.get(varMap2.keySet().toArray()[i]), varMap2.values().toArray()[i]);
            }
            for (int i = 0; i < varMap3.size(); i++) {
                assertEquals(variableMap3.get(varMap3.keySet().toArray()[i]), varMap3.values().toArray()[i]);
            }

            String[] keysOfVariableMap1InValueOrder = {"NO", "YES", "I DON'T KNOW"};
            String[] keysOfVariableMap2InValueOrder = {"CURRENT", "FORMER", "NEVER"};
            String[] keysOfVariableMap3InValueOrder = {"YES", "NO"};
            List<String[]> keysList = Arrays.asList(keysOfVariableMap1InValueOrder, keysOfVariableMap2InValueOrder, keysOfVariableMap3InValueOrder);
            for (int i = 0; i < list.size(); i++) {
                int count = 0;
                for (Map.Entry<String, Integer> entry : list.get(i).getCategoricalMap().entrySet()) {
                    String keyToCheck = keysList.get(i)[count];
                    assertEquals(entry.getKey(), keyToCheck);
                    count++;
                }
            }
        }
    }

    @Nested
    class continuousPathTests {

        @Test
        @DisplayName("Test Many filters")
        void TestManyRequiredContinuousFilters() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.numericFilters.put("\\DCC Harmonized data set\\blood_cell_count\\hemoglobin_mcnc_bld_1\\", new Filter.DoubleFilter());
            query.numericFilters.put("\\DCC Harmonized data set\\blood_cell_count\\platelet_ncnc_bld_1\\", new Filter.DoubleFilter(100.0, 45000.0));
            query.numericFilters.put("\\DCC Harmonized data set\\lipids\\hdl_1\\", new Filter.DoubleFilter(11.0, 150.0));
            query.expectedResultType = ResultType.CONTINUOUS_CROSS_COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap1 = new TreeMap<>();
            variableMap1.put("1.0", 20);
            int randomNumber = (int)Math.round(Math.random() * 100);
            for(int i = 0; i < randomNumber; i++) {
                double randomDouble = Math.round(Math.random() * 100);
                int randomCount = (int)Math.round(Math.random() * 100);
                variableMap1.put(String.valueOf(randomDouble), randomCount);
            }
            Map<String, Integer> variableMap2 = new TreeMap<>();
            randomNumber = (int)Math.round(Math.random() * 100);
            for(int i = 0; i < randomNumber; i++) {
                double randomDouble = getRandomNumber(100, 45000);
                int randomCount = (int)Math.round(Math.random() * 100);
                variableMap2.put(String.valueOf(randomDouble), randomCount);
            }

            Map<String, Integer> variableMap3 = new TreeMap<>();
            randomNumber = (int)Math.round(Math.random() * 100);
            for(int i = 0; i < randomNumber; i++) {
                double randomDouble = getRandomNumber(100, 45000);
                int randomCount = (int)Math.round(Math.random() * 100);
                variableMap3.put(String.valueOf(randomDouble), randomCount);
            }
            crossCountsMap.put("\\DCC Harmonized data set\\blood_cell_count\\hemoglobin_mcnc_bld_1\\", variableMap1);
            crossCountsMap.put("\\DCC Harmonized data set\\blood_cell_count\\platelet_ncnc_bld_1\\", variableMap2);
            crossCountsMap.put("\\DCC Harmonized data set\\lipids\\hdl_1\\", variableMap3);
            List<ContinuousData> list = dataProcessingService.getContinuousData(crossCountsMap);
            assertEquals(crossCountsMap.size(), list.size());
            assertEquals("Variable distribution of blood_cell_count: hemoglobin_mcnc_bld_1", list.get(0).getTitle());
            assertEquals("Variable distribution of blood_cell_count: platelet_ncnc_bld_1", list.get(1).getTitle());
            assertEquals("Variable distribution of lipids: hdl_1", list.get(2).getTitle());

            assertEquals("hemoglobin_mcnc_bld_1", list.get(0).getXAxisName());
            assertEquals("platelet_ncnc_bld_1", list.get(1).getXAxisName());
            assertEquals("hdl_1", list.get(2).getXAxisName());

            assertEquals("Number of Participants", list.get(0).getYAxisName());
            assertEquals("Number of Participants", list.get(1).getYAxisName());
            assertEquals("Number of Participants", list.get(2).getYAxisName());
        }

        @Test
        @DisplayName("Data accuracy test, no extra zeros added")
        void TestSingleDataAccuracyForNoExtraZero() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.numericFilters.put("\\DCC Harmonized data set\\blood_cell_count\\hemoglobin_mcnc_bld_1\\", new Filter.DoubleFilter());
            query.expectedResultType = ResultType.CONTINUOUS_CROSS_COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap1 = new TreeMap<>();
            variableMap1.put("1.0", 20);
            variableMap1.put("2.0", 10);
            variableMap1.put("3.0", 5);
            variableMap1.put("4.0", 3);
            variableMap1.put("5.0", 90);
            variableMap1.put("6.0", 100);
            variableMap1.put("7.0", 150);
            variableMap1.put("8.0", 200);
            variableMap1.put("9.0", 300);
            variableMap1.put("10.0", 20);
            variableMap1.put("11.0", 10);
            variableMap1.put("12.0", 5);
            variableMap1.put("13.0", 3);
            variableMap1.put("14.0", 90);
            variableMap1.put("15.0", 100);
            variableMap1.put("16.0", 150);
            variableMap1.put("17.0", 200);
            variableMap1.put("18.0", 300);
            variableMap1.put("19.0", 180);
            variableMap1.put("20.0", 100);
            variableMap1.put("30.0", 150);
            variableMap1.put("31.0", 104);
            variableMap1.put("32.0", 100);
            variableMap1.put("33.0", 80);

            Map<String, Integer> expectedOutputMap = new LinkedHashMap<>();
            expectedOutputMap.put("1.0 - 11.0", 908);
            expectedOutputMap.put("12.0 - 22.0", 1128);
            expectedOutputMap.put("23.0 +", 434);

            crossCountsMap.put("\\DCC Harmonized data set\\blood_cell_count\\hemoglobin_mcnc_bld_1\\", variableMap1);
            List<ContinuousData> list = dataProcessingService.getContinuousData(crossCountsMap);
            assertEquals(crossCountsMap.size(), list.size());
            assertEquals(expectedOutputMap.size(), list.get(0).getContinuousMap().size());
            assertEquals(expectedOutputMap, list.get(0).getContinuousMap());
        }

        @Test
        @DisplayName("Make Sure last value has plus and isn't a value of zero")
        void TestSingleDataAccuracyForPlusNotLastZero() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.numericFilters.put("\\DCC Harmonized data set\\blood_cell_count\\hemoglobin_mcnc_bld_1\\", new Filter.DoubleFilter());
            query.expectedResultType = ResultType.CONTINUOUS_CROSS_COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap1 = new TreeMap<>();
            variableMap1.put("1.0", 20);
            variableMap1.put("2.0", 10);
            variableMap1.put("3.0", 5);
            variableMap1.put("4.0", 3);
            variableMap1.put("5.0", 90);
            variableMap1.put("6.0", 100);
            variableMap1.put("7.0", 150);
            variableMap1.put("8.0", 200);
            variableMap1.put("9.0", 300);
            variableMap1.put("10.0", 20);
            variableMap1.put("11.0", 10);
            variableMap1.put("12.0", 5);
            variableMap1.put("13.0", 3);
            variableMap1.put("14.0", 90);
            variableMap1.put("15.0", 100);
            variableMap1.put("16.0", 150);
            variableMap1.put("17.0", 200);
            variableMap1.put("18.0", 300);
            variableMap1.put("19.0", 180);
            variableMap1.put("20.0", 100);
            variableMap1.put("51.0", 34);
            variableMap1.put("53.0", 34);
            variableMap1.put("54.0", 456);
            variableMap1.put("55.0", 34);
            variableMap1.put("56.0", 456);
            variableMap1.put("57.0", 34);
            variableMap1.put("58.0", 456);
            variableMap1.put("59.0", 34);
            variableMap1.put("60.0", 456);
            variableMap1.put("61.0", 34);
            variableMap1.put("62.0", 456);
            variableMap1.put("63.0", 34);
            variableMap1.put("64.0", 456);
            variableMap1.put("66.0", 456);
            variableMap1.put("67.0", 34);
            variableMap1.put("68.0", 456);
            variableMap1.put("69.0", 34);
            variableMap1.put("70.0", 456);
            variableMap1.put("71.0", 34);
            variableMap1.put("72.0", 456);
            variableMap1.put("73.0", 34);
            variableMap1.put("74.0", 456);
            variableMap1.put("75.0", 34);
            variableMap1.put("76.0", 456);
            variableMap1.put("77.0", 34);
            variableMap1.put("78.0", 456);
            variableMap1.put("79.0", 34);
            variableMap1.put("80.0", 456);
            variableMap1.put("81.0", 34);
            variableMap1.put("82.0", 456);
            variableMap1.put("83.0", 34);
            variableMap1.put("84.0", 456);
            variableMap1.put("85.0", 34);
            variableMap1.put("86.0", 456);
            variableMap1.put("87.0", 34);
            variableMap1.put("88.0", 456);
            variableMap1.put("89.0", 34);
            variableMap1.put("90.0", 456);
            variableMap1.put("91.0", 34);
            variableMap1.put("92.0", 456);
            variableMap1.put("93.0", 34);
            variableMap1.put("94.0", 456);
            variableMap1.put("95.0", 34);
            variableMap1.put("96.0", 456);
            variableMap1.put("97.0", 34);
            variableMap1.put("98.0", 456);
            variableMap1.put("99.0", 34);
            variableMap1.put("100.0", 456);
            variableMap1.put("101.0", 34);
            variableMap1.put("102.0", 456);
            variableMap1.put("103.0", 34);
            variableMap1.put("104.0", 456);
            variableMap1.put("105.0", 34);
            variableMap1.put("106.0", 456);
            variableMap1.put("107.0", 34);
            variableMap1.put("108.0", 456);
            variableMap1.put("109.0", 34);
            variableMap1.put("110.0", 456);
            variableMap1.put("111.0", 34);
            variableMap1.put("112.0", 456);
            variableMap1.put("113.0", 34);
            variableMap1.put("114.0", 456);
            variableMap1.put("115.0", 34);
            variableMap1.put("116.0", 456);
            variableMap1.put("117.0", 34);
            variableMap1.put("118.0", 456);
            variableMap1.put("119.0", 34);
            variableMap1.put("120.0", 456);


            Map<String, Integer> expectedOutputMap = new LinkedHashMap<>();
            expectedOutputMap.put("1.0 - 11.0", 908);
            expectedOutputMap.put("12.0 - 22.0", 1128);
            expectedOutputMap.put("23.0 +", 434);

            crossCountsMap.put("\\DCC Harmonized data set\\blood_cell_count\\hemoglobin_mcnc_bld_1\\", variableMap1);
            List<ContinuousData> list = dataProcessingService.getContinuousData(crossCountsMap);
            assertEquals(crossCountsMap.size(), list.size());
            List<Map.Entry<String,Integer>> entryList =
                    new ArrayList<>(list.get(0).getContinuousMap().entrySet());
            Map.Entry<String, Integer> lastEntry =
                    entryList.get(entryList.size()-1);
            assertTrue(lastEntry.getKey().contains("+"));
            assertTrue(lastEntry.getValue() != 0);
        }

        @Test
        @DisplayName("Make Sure Empty buckets are added")
        void TestSingleDataAccuracyForAddingMissingBucketsWithZeroValues() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.numericFilters.put("\\DCC Harmonized data set\\blood_cell_count\\hemoglobin_mcnc_bld_1\\", new Filter.DoubleFilter());
            query.expectedResultType = ResultType.CONTINUOUS_CROSS_COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap1 = new TreeMap<>();
            variableMap1.put("1.0", 20);
            variableMap1.put("2.0", 10);
            variableMap1.put("3.0", 5);
            variableMap1.put("4.0", 3);
            variableMap1.put("5.0", 90);
            variableMap1.put("6.0", 100);
            variableMap1.put("7.0", 150);
            variableMap1.put("8.0", 200);
            variableMap1.put("9.0", 300);
            variableMap1.put("10.0", 20);
            variableMap1.put("11.0", 10);
            variableMap1.put("12.0", 5);
            variableMap1.put("13.0", 3);
            variableMap1.put("14.0", 90);
            variableMap1.put("15.0", 100);
            variableMap1.put("16.0", 150);
            variableMap1.put("17.0", 200);
            variableMap1.put("18.0", 300);
            variableMap1.put("19.0", 180);
            variableMap1.put("20.0", 100);
            variableMap1.put("61.0", 34);
            variableMap1.put("62.0", 456);
            variableMap1.put("63.0", 34);
            variableMap1.put("64.0", 456);
            variableMap1.put("66.0", 456);
            variableMap1.put("67.0", 34);
            variableMap1.put("68.0", 456);
            variableMap1.put("69.0", 34);
            variableMap1.put("70.0", 456);
            variableMap1.put("71.0", 34);
            variableMap1.put("72.0", 456);
            variableMap1.put("73.0", 34);
            variableMap1.put("74.0", 456);
            variableMap1.put("75.0", 34);
            variableMap1.put("76.0", 456);
            variableMap1.put("77.0", 34);
            variableMap1.put("78.0", 456);
            variableMap1.put("79.0", 34);
            variableMap1.put("80.0", 456);
            variableMap1.put("81.0", 34);
            variableMap1.put("82.0", 456);
            variableMap1.put("83.0", 34);
            variableMap1.put("84.0", 456);
            variableMap1.put("85.0", 34);
            variableMap1.put("86.0", 456);
            variableMap1.put("87.0", 34);
            variableMap1.put("88.0", 456);
            variableMap1.put("89.0", 34);
            variableMap1.put("90.0", 456);
            variableMap1.put("91.0", 34);
            variableMap1.put("92.0", 456);
            variableMap1.put("93.0", 34);
            variableMap1.put("94.0", 456);
            variableMap1.put("95.0", 34);
            variableMap1.put("96.0", 456);
            variableMap1.put("97.0", 34);
            variableMap1.put("98.0", 456);
            variableMap1.put("99.0", 34);
            variableMap1.put("100.0", 456);
            variableMap1.put("101.0", 34);
            variableMap1.put("102.0", 456);
            variableMap1.put("103.0", 34);
            variableMap1.put("104.0", 456);
            variableMap1.put("105.0", 34);
            variableMap1.put("106.0", 456);
            variableMap1.put("107.0", 34);
            variableMap1.put("108.0", 456);
            variableMap1.put("109.0", 34);
            variableMap1.put("110.0", 456);
            variableMap1.put("111.0", 34);
            variableMap1.put("112.0", 456);
            variableMap1.put("113.0", 34);
            variableMap1.put("114.0", 456);
            variableMap1.put("115.0", 34);
            variableMap1.put("116.0", 456);
            variableMap1.put("117.0", 34);
            variableMap1.put("118.0", 456);
            variableMap1.put("119.0", 34);
            variableMap1.put("120.0", 456);


            Map<String, Integer> expectedOutputMap = new LinkedHashMap<>();
            expectedOutputMap.put("1.0 - 30.0", 2036);
            expectedOutputMap.put("31.0 - 60.0", 0);
            expectedOutputMap.put("61.0 - 90.0", 7316);
            expectedOutputMap.put("91.0 +", 7350);

            crossCountsMap.put("\\DCC Harmonized data set\\blood_cell_count\\hemoglobin_mcnc_bld_1\\", variableMap1);
            List<ContinuousData> list = dataProcessingService.getContinuousData(crossCountsMap);
            assertEquals(crossCountsMap.size(), list.size());
            assertEquals(expectedOutputMap, list.get(0).getContinuousMap());
        }

        @Test
        @DisplayName("Test Big data set has more than 1 column when max-min>numBins")
        void TestBigDataSetHasMoreThenOneColumnWhenNumBinsGreaterThanMaxMinusMin() {
            QueryRequest goodQueryRequest = new QueryRequest();
            goodQueryRequest.setResourceUUID(UUID.randomUUID());
            goodQueryRequest.getResourceCredentials().put("Authorization", "some token");
            Query query = new Query();
            query.numericFilters.put("\\DCC Harmonized data set\\baseline_common_covariates\\bmi_baseline_1\\", new Filter.DoubleFilter());
            query.expectedResultType = ResultType.CONTINUOUS_CROSS_COUNT;
            goodQueryRequest.setQuery(query);
            Map<String, Map<String, Integer>> crossCountsMap = new LinkedHashMap<>();
            Map<String, Integer> variableMap1 = new TreeMap<>();
            // Add real min and max
            variableMap1.put("11.1056364319709",1);
            variableMap1.put("91.797262447927",2);
            //Most values were one
            boolean useOne = false;
            // Majority of people are between 18.5 and 24.9 bmi, these numbers help get a realistic distribution
            // this makes the calculation of standard deviation create the scenario for this test and ultimately creates
            // a small bin size.
            int n = 209043/50*49;
            double min = 18.5;
            double max = 24.9;
            while (n >=0) {
                int numParts = (int)Math.floor(getRandomNumber(1, 3));
                if (useOne && numParts!=1) {
                    numParts = 1;
                    useOne = !useOne;
                }
                String key = String.valueOf(getRandomDouble(min, max));
                variableMap1.put(key,numParts);
                n -= numParts;
            }
            // Fill in the last 1/50th of the samples with 0;
            n = 209040/50;
            min = 11.1056364319709;
            max = 91.797262447927;
            while (n >=0) {
                String key = String.valueOf(getRandomDouble(min, max));
                variableMap1.put(key, 1);
                n -= 1;
            }
            crossCountsMap.put("\\DCC Harmonized data set\\baseline_common_covariates\\bmi_baseline_1\\", variableMap1);
            List<ContinuousData> list = dataProcessingService.getContinuousData(crossCountsMap);
            assertEquals(1, list.size());
            assertTrue(1 < list.get(0).getContinuousMap().size());
        }

//        @Test
//        void TestBigDataSetHasMoreThenOneColumnWhenNumBinsGreaterThanMaxMinusMinALot() {
//            for(int i=0; i < 1000; i++) {
//                System.out.println(i);
//                TestBigDataSetHasMoreThenOneColumnWhenNumBinsGreaterThanMaxMinusMin();
//            }
//        }

        private double getRandomNumber(int min, int max) {
            return ((Math.random() * (max - min)) + min);
        }

        private double getRandomDouble(double min, double max) {
            return ((Math.random() * (max - min)) + min);
        }
    }
}
