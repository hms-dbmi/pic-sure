package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.dataset.Dataset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
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
@ActiveProfiles("test")
class ConceptServiceIntegrationTest {

    @Autowired
    ConceptService subject;

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
    void shouldGetDetails() {
        Optional<Concept> actual = subject.conceptDetail("phs000007", "\\phs000007\\pht000021\\phv00003844\\FL200\\");

        CategoricalConcept table = new CategoricalConcept(
            "\\phs000007\\pht000021\\", "pht000021", "ex0_19s", "phs000007", "Clinic Exam, Original Cohort Exam 19", List.of(), true, "FHS",
            null, Map.of("description", "Clinic Exam, Original Cohort Exam 19"), null, null
        );
        Dataset study = new Dataset(
            "phs000007", "Framingham Cohort", "FHS",
            "Startup of Framingham Heart Study. Cardiovascular disease (CVD) is the leading cause of death and serious illness in the United States. In 1948, the Framingham Heart Study (FHS) -- under the direction of the National Heart Institute (now known as the National Heart, Lung, and Blood Institute, NHLBI) -- embarked on a novel and ambitious project in health research. At the time, little was known about the general causes of heart disease and stroke, but the death rates for CVD had been increasing steadily since the beginning of the century and had become an American epidemic.\\n\\nThe objective of the FHS was to identify the common factors or characteristics that contribute to CVD by following its development over a long period of time in a large group of participants who had not yet developed overt symptoms of CVD or suffered a heart attack or stroke.\\n\\nDesign of Framingham Heart Study. In 1948, the researchers recruited 5,209 men and women between the ages of 30 and 62 from the town of Framingham, Massachusetts, and began the first round of extensive physical examinations and lifestyle interviews that they would later analyze for common patterns related to CVD development. Since 1948, the subjects have returned to the study every two years for an examination consisting of a detailed medical history, physical examination, and laboratory tests, and in 1971, the study enrolled a second-generation cohort -- 5,124 of the original participants' adult children and their spouses -- to participate in similar examinations. The second examination of the Offspring cohort occurred eight years after the first examination, and subsequent examinations have occurred approximately every four years thereafter. In April 2002 the Study entered a new phase: the enrollment of a third generation of participants, the grandchildren of the original cohort. The first examination of the Third Generation Study was completed in July 2005 and involved 4,095 participants. Thus, the FHS has evolved into a prospective, community-based, three generation family study. The FHS is a joint project of the National Heart, Lung and Blood Institute and Boston University.\\n\\nResearch Areas in the Framingham Heart Study. Over the years, careful monitoring of the FHS population has led to the identification of the major CVD risk factors -- high blood pressure, high blood cholesterol, smoking, obesity, diabetes, and physical inactivity -- as well as a great deal of valuable information on the effects of related factors such as blood triglyceride and HDL cholesterol levels, age, gender, and psychosocial issues. Risk factors have been identified for the major components of CVD, including coronary heart disease, stroke, intermittent claudication, and heart failure. It is also clear from research in the FHS and other studies that substantial subclinical vascular disease occurs in the blood vessels, heart and brain that precedes clinical CVD. With recent advances in technology, the FHS has enhanced its research capabilities and capitalized on its inherent resources by the conduct of high resolution imaging to detect and quantify subclinical vascular disease in the major blood vessels, heart and brain. These studies have included ultrasound studies of the heart (echocardiography) and carotid arteries, computed tomography studies of the heart and aorta, and magnetic resonance imaging studies of the brain, heart, and aorta. Although the Framingham cohort is primarily white, the importance of the major CVD risk factors identified in this group have been shown in other studies to apply almost universally among racial and ethnic groups, even though the patterns of distribution may vary from group to group. In the past half century, the Study has produced approximately 1,200 articles in leading medical journals. The concept of CVD risk factors has become an integral part of the modern medical curriculum and has led to the development of effective treatment and preventive strategies in clinical practice.\\n\\nIn addition to research studies focused on risk factors, subclinical CVD and clinically apparent CVD, Framingham investigators have also collaborated with leading researchers from around the country and throughout the world on projects involving some of the major chronic illnesses in men and women, including dementia, osteoporosis and arthritis, nutritional deficiencies, eye diseases, hearing disorders, and chronic obstructive lung diseases.\\n\\nGenetic Research in the Framingham Heart Study. While pursuing the Study's established research goals, the NHLBI and the Framingham investigators has expanded its research mission into the study of genetic factors underlying CVD and other disorders. Over the past two decades, DNA has been collected from blood samples and from immortalized cell lines obtained from Original Cohort participants, members of the Offspring Cohort and the Third Generation Cohort. Several large-scale genotyping projects have been conducted in the past decade. Genome-wide linkage analysis has been conducted using genotypes of approximately 400 microsatellite markers that have been completed in over 9,300 subjects in all three generations. Analyses using microsatellite markers completed in the original cohort and offspring cohorts have resulted in over 100 publications, including many publications from the Genetics Analysis Workshop 13. Several other recent collaborative projects have completed thousands of SNP genotypes for candidate gene regions in subsets of FHS subjects with available DNA. These projects include the Cardiogenomics Program of the NHLBI's Programs for Genomics Applications, the genotyping of ~3000 SNPs in inflammation genes, and the completion of a genome-wide scan of 100,000 SNPs using the Affymetrix 100K Genechip.\\n\\nFramingham Cohort Phenotype Data. The phenotype database contains a vast array of phenotype information available in all three generations. These will include the quantitative measures of the major risk factors such as systolic blood pressure, total and HDL cholesterol, fasting glucose, and cigarette use, as well as anthropomorphic measures such as body mass index, biomarkers such as fibrinogen and CRP, and electrocardiography measures such as the QT interval. Many of these measures have been collected repeatedly in the original and offspring cohorts. Also included in the SHARe database will be an array of recently collected biomarkers, subclinical disease imaging measures, clinical CVD outcomes as well as an array of ancillary studies. The phenotype data is located here in the top-level study phs000007 Framingham Cohort. To view the phenotype variables collected from the Framingham Cohort, please click on the Variables tab above."
        );
        ContinuousConcept expected = new ContinuousConcept(
            "\\phs000007\\pht000021\\phv00003844\\FL200\\", "phv00003844", "FL200", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY",
            true, 0F, 3F, "FHS",
            Map.of(
                "unique_identifier", "no", "stigmatizing", "no", "bdc_open_access", "yes", "values", "[0, 3]", "description",
                "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", "free_text", "no"
            ), null, table, study
        );

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(expected, actual.get());
    }
}
