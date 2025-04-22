package edu.harvard.dbmi.avillach.dictionary.dump;

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

import java.util.Arrays;
import java.util.List;

@Testcontainers
@SpringBootTest
class DumpRepositoryTest {

    @Autowired
    DumpRepository subject;

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
    void shouldDumpConcepts() {
        Pageable page = Pageable.ofSize(10).first();
        List<List<String>> actual = subject.getRowsForTable(page, DumpTable.ConceptNode);
        List<List<String>> expected = List.of(
            Arrays.asList("180", "14", "", "", "categorical ", "\\ACT Diagnosis ICD-10\\", null, "'-10':4 'act':1 'diagnosi':2 'icd':3"),
            Arrays.asList(
                "181", "14", "", "", "categorical ", "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\",
                "180", "'-10':4 'act':1 'diagnosi':2 'diseas':8 'icd':3 'j00':6,14 'j00-j99':5,13 'j99':7,15 'respiratori':11 'system':12"
            ),
            Arrays.asList(
                "182", "14", "", "", "categorical ",
                "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\",
                "181",
                "'-10':4 'act':1 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'system':12"
            ),
            Arrays.asList(
                "183", "14", "", "", "categorical ",
                "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\",
                "182",
                "'-10':4 'act':1 'asthma':27 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'system':12"
            ),
            Arrays.asList(
                "184", "14", "", "", "categorical ",
                "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\",
                "183",
                "'-10':4 'act':1 'asthma':27,31 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.5':28 'j47':18,25 'j99':7,15 'lower':20 'persist':30 'respiratori':11,21 'sever':29 'system':12"
            ),
            Arrays.asList(
                "185", "14", "J45.51 Severe persistent asthma with (acute) exacerbation",
                "J45.51 Severe persistent asthma with (acute) exacerbation", "categorical ",
                "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\J45.51 Severe persistent asthma with (acute) exacerbation\\",
                "184",
                "'-10':4 'act':1 'acut':37,44 'asthma':27,31,35,42 'chronic':19 'diagnosi':2 'diseas':8,22 'exacerb':38,45 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.5':28 'j45.51':32,39 'j47':18,25 'j99':7,15 'lower':20 'persist':30,34,41 'respiratori':11,21 'sever':29,33,40 'system':12"
            ),
            Arrays.asList(
                "186", "14", "J45.52 Severe persistent asthma with status asthmaticus",
                "J45.52 Severe persistent asthma with status asthmaticus", "categorical ",
                "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\J45.52 Severe persistent asthma with status asthmaticus\\",
                "184",
                "'-10':4 'act':1 'allerg':50,57,72,81 'approxim':46 'asthma':27,31,35,42,51,58,64,70,79,89 'asthmaticus':38,45,54,61,67,76,85,92 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.5':28 'j45.52':32,39,86 'j47':18,25 'j99':7,15 'lower':20 'persist':30,34,41,49,56,63,69,78,88 'respiratori':11,21 'rhiniti':73,82 'sever':29,33,40,48,55,62,68,77,87 'status':37,44,53,60,66,75,84,91 'synonym':47 'system':12"
            ),
            Arrays.asList(
                "187", "14", "", "", "categorical ",
                "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\",
                "183",
                "'-10':4 'act':1 'asthma':27,32 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.9':28 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'system':12 'unspecifi':31"
            ),
            Arrays.asList(
                "188", "14", "", "", "categorical ",
                "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\J45.90 Unspecified asthma\\",
                "187",
                "'-10':4 'act':1 'asthma':27,32,35 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.9':28 'j45.90':33 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'system':12 'unspecifi':31,34"
            ),
            Arrays.asList(
                "189", "14", "J45.901 Unspecified asthma with (acute) exacerbation", "J45.901 Unspecified asthma with (acute) exacerbation",
                "categorical ",
                "\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\J45.90 Unspecified asthma\\J45.901 Unspecified asthma with (acute) exacerbation\\",
                "188",
                "'-10':4 'act':1 'acut':40,46,50,60,64,74,83 'allerg':55,57,71 'approxim':48 'asthma':27,32,35,38,44,53,58,62,69,78,81 'chronic':19 'diagnosi':2 'diseas':8,22 'exacerb':41,47,51,61,65,75,76,84 'flare':67 'flare-up':66 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.9':28 'j45.90':33 'j45.901':36,42,79 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'rhiniti':56,72 'synonym':49 'system':12 'unspecifi':31,34,37,43,80"
            )
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldReturnEmptyPastEnd() {
        Pageable page = Pageable.ofSize(10).withPage(100);
        List<List<String>> actual = subject.getRowsForTable(page, DumpTable.ConceptNodeMeta);
        List<List<String>> expected = List.of();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetConceptNodeMeta() {
        Pageable page = Pageable.ofSize(1).first();
        List<List<String>> actual = subject.getRowsForTable(page, DumpTable.ConceptNodeMeta);
        List<List<String>> expected = List.of(
            Arrays.asList(
                "19", "186", "description",
                "Approximate Synonyms:\n" + "Severe persistent allergic asthma in status asthmaticus\n"
                    + "Severe persistent allergic asthma with status asthmaticus\n" + "Severe persistent asthma in status asthmaticus\n"
                    + "Severe persistent asthma with allergic rhinitis in status asthmaticus\n"
                    + "Severe persistent asthma with allergic rhinitis with status asthmaticus"
            )
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetFacet() {
        Pageable page = Pageable.ofSize(1).first();
        List<List<String>> actual = subject.getRowsForTable(page, DumpTable.Facet);
        List<List<String>> expected =
            List.of(Arrays.asList("18", "2", "taps_tool", "NIDA CTN Common Data Elements = TAPS Tool", null, null));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetFacetConceptNode() {
        Pageable page = Pageable.ofSize(1).first();
        List<List<String>> actual = subject.getRowsForTable(page, DumpTable.FacetConceptNode);
        List<List<String>> expected = List.of(Arrays.asList("1", "22", "180"));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetFacetCategory() {
        Pageable page = Pageable.ofSize(1).first();
        List<List<String>> actual = subject.getRowsForTable(page, DumpTable.FacetCategory);
        List<List<String>> expected = List.of(Arrays.asList("1", "study_ids_dataset_ids", "Study IDs/Dataset IDs", ""));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetFacetMeta() {
        Pageable page = Pageable.ofSize(1).first();
        List<List<String>> actual = subject.getRowsForTable(page, DumpTable.FacetMeta);
        List<List<String>> expected = List.of(Arrays.asList("1", "25", "full_name", "National Sleep Research Resource"));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetFacet_ConceptNode() {
        Pageable page = Pageable.ofSize(1).first();
        List<List<String>> actual = subject.getRowsForTable(page, DumpTable.Facet_ConceptNode);
        List<List<String>> expected = List.of(Arrays.asList("1", "22", "180"));

        Assertions.assertEquals(expected, actual);
    }
}
