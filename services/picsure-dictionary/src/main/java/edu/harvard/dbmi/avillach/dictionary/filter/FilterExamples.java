package edu.harvard.dbmi.avillach.dictionary.filter;

/**
 * OpenAPI text shared by the operations that take a {@link Filter}: how to fill the filter, and request bodies that run as they are against
 * the all-in-one's public datasets. Public only so the concept and facet controllers can reference it; not part of any wire contract.
 */
public final class FilterExamples {

    /** How to fill each field of a filter body, shared by the concept search and the facet counts. */
    public static final String FILTER_GUIDE = """
        **Body**: a filter. Every field is optional, and `{}` matches every concept.

        - `search`: words to find in concept names, descriptions and values. Punctuation is replaced by spaces.
        - `facets`: facets to narrow by, each `{"name": ..., "category": ...}` exactly as `POST /facets` returns them; other \
        fields are ignored. Facets in the same category are OR'd, and different categories are AND'd.
        - `consents`: the consents of the user you search for, as `GET /psama/user/me/consents` lists them under \
        `\\_consents\\` (for example `phs000007.c1`). Only those datasets' concepts are returned. A missing or empty list \
        applies no restriction and returns every concept, so wait until the user's consents have loaded before you search.
        """;

    /** Every concept whose text matches "age". */
    public static final String SEARCH = "{\"search\":\"age\"}";

    /** Concepts matching "age" in one dataset. */
    public static final String SEARCH_IN_DATASET = "{\"search\":\"age\",\"facets\":[{\"name\":\"Synthea\",\"category\":\"dataset_id\"}]}";

    /** Every concept in either of two datasets: facets in one category are OR'd. */
    public static final String EITHER_DATASET =
        "{\"facets\":[{\"name\":\"Synthea\",\"category\":\"dataset_id\"},{\"name\":\"Nhanes\",\"category\":\"dataset_id\"}]}";

    /** Continuous concepts in one dataset: facets in different categories are AND'd. */
    public static final String CONTINUOUS_IN_DATASET =
        "{\"facets\":[{\"name\":\"Synthea\",\"category\":\"dataset_id\"},{\"name\":\"continuous\",\"category\":\"data_type\"}]}";

    /** A search limited to the datasets a user's consents cover. */
    public static final String SEARCH_WITH_CONSENTS = "{\"search\":\"age\",\"consents\":[\"phs000007.c1\",\"phs000007.c2\"]}";

    /** No restriction: counts or lists every concept. */
    public static final String EVERYTHING = "{}";

    private FilterExamples() {}
}
