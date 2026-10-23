package edu.harvard.dbmi.avillach.dictionary;

/**
 * The environment-specific values the dictionary's OpenAPI examples name, each a {@code picsure.openapi.examples} placeholder whose default
 * exists in the all-in-one's public data. A deployment whose data differs sets the keys, and the shared OpenAPI customizer resolves them
 * when the document is built. Inside a JSON example body, use the {@code _IN_JSON} form of a concept path: its default is escaped as JSON
 * requires, and the customizer escapes a configured value after the body is parsed.
 */
public final class ExampleValues {

    /** Dataset the dictionary examples use, the first segment of every example concept path. */
    public static final String DATASET = "${picsure.openapi.examples.dataset:Synthea}";

    /** A second dataset, used where an example combines two datasets. */
    public static final String SECOND_DATASET = "${picsure.openapi.examples.second-dataset:Nhanes}";

    /** Name of the facet category that groups concepts by dataset, such as study_ids_dataset_ids on BioData Catalyst. */
    public static final String DATASET_FACET_CATEGORY = "${picsure.openapi.examples.dataset-facet-category:dataset_id}";

    /** Name of the facet category that groups concepts by variable type. */
    public static final String TYPE_FACET_CATEGORY = "${picsure.openapi.examples.type-facet-category:data_type}";

    /** Name of the continuous facet within the variable type category. */
    public static final String CONTINUOUS_TYPE_FACET = "${picsure.openapi.examples.continuous-type-facet:continuous}";

    /** A categorical concept path in the example dataset. */
    public static final String CATEGORICAL_CONCEPT_PATH =
        "${picsure.openapi.examples.categorical-concept-path:\\Synthea\\ACT Demographics\\Sex\\}";

    /** A continuous concept path in the example dataset. */
    public static final String CONTINUOUS_CONCEPT_PATH =
        "${picsure.openapi.examples.continuous-concept-path:\\Synthea\\ACT Demographics\\Age\\}";

    /** A concept path with children, used by the tree example. */
    public static final String PARENT_CONCEPT_PATH = "${picsure.openapi.examples.parent-concept-path:\\Synthea\\ACT Demographics\\}";

    /** Search term the examples search for. */
    public static final String SEARCH_TERM = "${picsure.openapi.examples.search-term:age}";

    /** A consent, a study accession with its consent group, used by the consents example. */
    public static final String CONSENT = "${picsure.openapi.examples.consent:phs000007.c1}";

    /** A second consent, used by the consents example. */
    public static final String SECOND_CONSENT = "${picsure.openapi.examples.second-consent:phs000007.c2}";

    /** {@link #CATEGORICAL_CONCEPT_PATH} with its default escaped for use inside a JSON string. */
    public static final String CATEGORICAL_CONCEPT_PATH_IN_JSON =
        "${picsure.openapi.examples.categorical-concept-path:\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\}";

    /** {@link #CONTINUOUS_CONCEPT_PATH} with its default escaped for use inside a JSON string. */
    public static final String CONTINUOUS_CONCEPT_PATH_IN_JSON =
        "${picsure.openapi.examples.continuous-concept-path:\\\\Synthea\\\\ACT Demographics\\\\Age\\\\}";

    /** {@link #PARENT_CONCEPT_PATH} with its default escaped for use inside a JSON string. */
    public static final String PARENT_CONCEPT_PATH_IN_JSON =
        "${picsure.openapi.examples.parent-concept-path:\\\\Synthea\\\\ACT Demographics\\\\}";

    private ExampleValues() {}
}
