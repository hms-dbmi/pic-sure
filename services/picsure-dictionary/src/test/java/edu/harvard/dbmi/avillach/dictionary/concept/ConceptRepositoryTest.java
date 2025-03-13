package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptShell;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Testcontainers
@SpringBootTest
class ConceptRepositoryTest {

    @Autowired
    ConceptRepository subject;

    @Container
    static final PostgreSQLContainer<?> databaseContainer = new PostgreSQLContainer<>("postgres:16").withReuse(true)
        .withCopyFileToContainer(MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql");

    @DynamicPropertySource
    static void mySQLProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Test
    void shouldMarkConceptThatHasNoStimatizedMetaAsAllowFiltering() {
        Boolean actual = subject.getConcept("1", "\\Variant Data Type\\WGS\\").map(Concept::allowFiltering).get();
        Assertions.assertTrue(actual);
    }

    @Test
    void shouldListAllConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "", List.of()), Pageable.unpaged());

        Assertions.assertEquals(31, actual.size());
    }

    @Test
    void shouldListFirstTwoConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "", List.of()), Pageable.ofSize(2).first());
        List<String> expectedPaths = List.of(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\J45.52 Severe persistent asthma with status asthmaticus\\",
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\J45.90 Unspecified asthma\\J45.901 Unspecified asthma with (acute) exacerbation\\"
        );

        Assertions.assertEquals(expectedPaths, actual.stream().map(Concept::conceptPath).toList());
    }

    @Test
    void shouldListNextTwoConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "", List.of()), Pageable.ofSize(2).first().next());
        List<String> expectedPaths = List.of(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\J45.90 Unspecified asthma\\J45.902 Unspecified asthma with status asthmaticus\\",
            "\\Bio Specimens\\HumanFluid\\Blood (Whole)\\SPECIMENS:HF.BLD.000 Quantity\\"
        );

        Assertions.assertEquals(expectedPaths, actual.stream().map(Concept::conceptPath).toList());
    }

    @Test
    void shouldFilterConceptsByFacet() {
        List<Concept> actual = subject.getConcepts(
            new Filter(List.of(new Facet("phs000007", "", "", "", 1, null, "study_ids_dataset_ids", null)), "", List.of()),
            Pageable.unpaged()
        );
        List<? extends Record> expected = List.of(
            new ContinuousConcept(
                "\\phs000007\\pht000021\\phv00003844\\FL200\\", "phv00003844", "FL200", "phs000007",
                "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0F, 3F, "FHS", null
            ),
            new ContinuousConcept(
                "\\phs000007\\pht000022\\phv00004260\\FM219\\", "phv00004260", "FM219", "phs000007",
                "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0F, 1F, "FHS", null
            ),
            new ContinuousConcept(
                "\\phs000007\\pht000033\\phv00008849\\D080\\", "phv00008849", "D080", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA/DAY",
                true, 0F, 5F, "FHS", null
            )
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterBySearch() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "COLA", List.of()), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new ContinuousConcept(
                "\\phs000007\\pht000021\\phv00003844\\FL200\\", "phv00003844", "FL200", "phs000007",
                "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0F, 3F, "FHS", null
            ),
            new ContinuousConcept(
                "\\phs000007\\pht000022\\phv00004260\\FM219\\", "phv00004260", "FM219", "phs000007",
                "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0F, 1F, "FHS", null
            ),
            new ContinuousConcept(
                "\\phs000007\\pht000033\\phv00008849\\D080\\", "phv00008849", "D080", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA/DAY",
                true, 0F, 5F, "FHS", null
            )
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterByBothSearchAndFacet() {
        List<Concept> actual = subject.getConcepts(
            new Filter(List.of(new Facet("phs002715", "", "", "", 1, null, "study_ids_dataset_ids", null)), "phs002715", List.of()),
            Pageable.unpaged()
        );
        List<? extends Record> expected = List.of(
            new CategoricalConcept(
                "\\phs002715\\age\\", "AGE_CATEGORY", "age", "phs002715", "Participant's age (category)", List.of("21"), true, "NSRR CFS",
                null, null
            ),
            new CategoricalConcept(
                "\\phs002715\\nsrr_ever_smoker\\", "nsrr_ever_smoker", "nsrr_ever_smoker", "phs002715", "Smoker status", List.of("yes"),
                true, "NSRR CFS", null, null
            )
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetCount() {
        long actual = subject.countConcepts(new Filter(List.of(), "", List.of()));

        Assertions.assertEquals(31L, actual);
    }

    @Test
    void shouldGetCountWithFilter() {
        Long actual = subject
            .countConcepts(new Filter(List.of(new Facet("phs002715", "", "", "", 1, null, "study_ids_dataset_ids", null)), "", List.of()));
        Assertions.assertEquals(2L, actual);
    }

    @Test
    void shouldGetDetailForConcept() {
        ContinuousConcept expected = new ContinuousConcept(
            "\\phs000007\\pht000033\\phv00008849\\D080\\", "phv00008849", "D080", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA/DAY", true,
            0F, 5F, "FHS", null
        );
        Optional<Concept> actual = subject.getConcept("phs000007", "\\phs000007\\pht000033\\phv00008849\\D080\\");

        Assertions.assertEquals(Optional.of(expected), actual);
    }

    @Test
    void shouldGetMetaForConcept() {
        Map<String, String> actual = subject.getConceptMeta("AGE_CATEGORY", "\\phs002715\\age\\");
        Map<String, String> expected = Map.of();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldNotGetConceptThatDNE() {
        Optional<Concept> actual = subject.getConcept("invalid.invalid", "fake");
        Assertions.assertEquals(Optional.empty(), actual);

        actual = subject.getConcept("fake", "\\\\B\\\\2\\\\Z\\\\");
        Assertions.assertEquals(Optional.empty(), actual);
    }

    @Test
    void shouldGetStigmatizedConcept() {
        Optional<Concept> actual = subject.getConcept("phs002385", "\\phs002385\\TXNUM\\");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertFalse(actual.get().allowFiltering());
    }

    @Test
    void shouldGetMetaForMultipleConcepts() {
        List<Concept> concepts = List.of(
            new ContinuousConcept(
                "\\phs000007\\pht000022\\phv00004260\\FM219\\", "", "", "phs000007", "", true, null, null, "FHS", Map.of()
            ),
            new ContinuousConcept("\\phs000007\\pht000033\\phv00008849\\D080\\", "", "", "phs000007", "", true, null, null, "FHS", Map.of())
        );

        Map<Concept, Map<String, String>> actual = subject.getConceptMetaForConcepts(concepts);
        Map<Concept, Map<String, String>> expected = Map.of(
            new ConceptShell("\\phs000007\\pht000033\\phv00008849\\D080\\", "phs000007"),
            Map.of(
                "unique_identifier", "false", "stigmatized", "false", "bdc_open_access", "true", "values", "[0.57,6.77]", "description",
                "# 12 OZ CUPS OF CAFFEINATED COLA/DAY", "free_text", "false"
            ), new ConceptShell("\\phs000007\\pht000022\\phv00004260\\FM219\\", "phs000007"),
            Map.of(
                "unique_identifier", "false", "stigmatized", "false", "bdc_open_access", "true", "values", "[0, 1]", "description",
                "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", "free_text", "false"
            )
        );
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetTree() {
        Concept d0 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\", "1");
        Concept d1 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", "1");
        Concept d2 = new CategoricalConcept(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\",
            "1"
        );
        Concept d3 = new CategoricalConcept(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\",
            "1"
        );
        Concept d4A = new CategoricalConcept(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\",
            "1"
        );
        Concept d4B = new CategoricalConcept(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified );asthma\\",
            "1"
        );
        d3 = d3.withChildren(List.of(d4A, d4B));
        d2.withChildren(List.of(d3));
        d1.withChildren(List.of(d2));
        d0.withChildren(List.of(d1));

        Optional<Concept> actual =
            subject.getConceptTree("1", "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", 3);
        Optional<Concept> expected = Optional.of(d0);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetTreeForDepthThatExceedsOntology() {
        Concept d0 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\", "1");
        Concept d1 = new CategoricalConcept("\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", "1");
        Concept d2 = new CategoricalConcept(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\",
            "1"
        );
        Concept d3 = new CategoricalConcept(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\",
            "1"
        );
        Concept d4A = new CategoricalConcept(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\",
            "1"
        );
        Concept d4B = new CategoricalConcept(
            "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified );asthma\\",
            "1"
        );
        d3 = d3.withChildren(List.of(d4A, d4B));
        d2.withChildren(List.of(d3));
        d1.withChildren(List.of(d2));
        d0.withChildren(List.of(d1));

        Optional<Concept> actual =
            subject.getConceptTree("1", "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", 30);
        Optional<Concept> expected = Optional.of(d0);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldReturnEmptyTreeForDNE() {
        Optional<Concept> actual = subject.getConceptTree("1", "\\ACT Top Secret ICD-69\\", 30);
        Optional<Concept> expected = Optional.empty();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldReturnEmptyForNegativeDepth() {
        Optional<Concept> actual =
            subject.getConceptTree("1", "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\", -1);
        Optional<Concept> expected = Optional.empty();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetStigmatizingConcept() {
        Optional<Concept> actual = subject.getConcept("phs002385", "\\phs002385\\TXNUM\\");
        ContinuousConcept expected = new ContinuousConcept(
            "\\phs002385\\TXNUM\\", "TXNUM", "TXNUM", "phs002385", "Transplant Number", false, 0F, 0F, "HCT_for_SCD", Map.of()
        );

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(expected, actual.get());
    }

    @Test
    void shouldGetContConceptWithSciNotation() {
        double min = 5.0E-21;
        double max = 7.0E33;

        Optional<Concept> actual = subject.getConcept("phs000284", "\\phs000284\\pht001902\\phv00122507\\age\\");

        Assertions.assertTrue(actual.isPresent());
        ContinuousConcept concept = (ContinuousConcept) actual.get();
        Assertions.assertEquals((float) min, concept.min());
        Assertions.assertEquals((float) max, concept.max());
    }

    @Test
    void shouldGetContConceptWithDecimalNotation() {
        Optional<Concept> actual = subject.getConcept("phs000007", "\\phs000007\\pht000033\\phv00008849\\D080\\");

        Assertions.assertTrue(actual.isPresent());
        ContinuousConcept concept = (ContinuousConcept) actual.get();
        Assertions.assertEquals(0.57f, concept.min());
        Assertions.assertEquals(6.77f, concept.max());
    }

    @Test
    void shouldGetConceptsByConceptPath() {
        List<String> conceptPaths = List.of(
            "\\phs002385\\TXNUM\\", "\\phs000284\\pht001902\\phv00122507\\age\\", "\\phs000007\\pht000022" + "\\phv00004260\\FM219\\",
            "\\NHANES\\examination\\physical fitness\\Stage 1 heart rate (per min)", "\\phs000007\\pht000021" + "\\phv00003844\\FL200\\",
            "\\phs002715\\age\\"
        );
        List<Concept> conceptsByPath = subject.getConceptsByPathWithMetadata(conceptPaths);
        Assertions.assertFalse(conceptsByPath.isEmpty());
        Assertions.assertEquals(6, conceptsByPath.size());
    }

    @Test
    void shouldGetSameConceptMetaAsConceptDetails() {
        List<String> conceptPaths = List.of("\\phs002385\\TXNUM\\", "\\phs000284\\pht001902\\phv00122507\\age\\");
        List<Concept> conceptsByPath = subject.getConceptsByPathWithMetadata(conceptPaths);
        Assertions.assertFalse(conceptsByPath.isEmpty());

        // Verify the meta data is correctly retrieve by comparing against known good query.
        Concept concept = conceptsByPath.getFirst();
        Map<String, String> expectedMeta = subject.getConceptMeta(concept.dataset(), concept.conceptPath());

        // compare the maps to each other.
        Map<String, String> actualMeta = concept.meta();
        Assertions.assertEquals(actualMeta, expectedMeta);
    }

}
