package edu.harvard.dbmi.avillach.dictionary.filter;

import static edu.harvard.dbmi.avillach.dictionary.ExampleValues.*;

/**
 * OpenAPI text shared by the operations that take a {@link Filter}: how to fill the filter, and request bodies built from
 * {@link edu.harvard.dbmi.avillach.dictionary.ExampleValues}, which run as they are against the all-in-one's public datasets unless a
 * deployment configures its own values. Public only so the concept and facet controllers can reference it; not part of any wire contract.
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

    /** Every concept whose text matches the example search term. */
    public static final String SEARCH = "{\"search\":\"" + SEARCH_TERM + "\"}";

    /** Concepts matching the search term in one dataset. */
    public static final String SEARCH_IN_DATASET =
        "{\"search\":\"" + SEARCH_TERM + "\",\"facets\":[{\"name\":\"" + DATASET + "\",\"category\":\"" + DATASET_FACET_CATEGORY + "\"}]}";

    /** Every concept in either of two datasets: facets in one category are OR'd. */
    public static final String EITHER_DATASET = "{\"facets\":[{\"name\":\"" + DATASET + "\",\"category\":\"" + DATASET_FACET_CATEGORY
        + "\"},{\"name\":\"" + SECOND_DATASET + "\",\"category\":\"" + DATASET_FACET_CATEGORY + "\"}]}";

    /** Continuous concepts in one dataset: facets in different categories are AND'd. */
    public static final String CONTINUOUS_IN_DATASET = "{\"facets\":[{\"name\":\"" + DATASET + "\",\"category\":\"" + DATASET_FACET_CATEGORY
        + "\"},{\"name\":\"" + CONTINUOUS_TYPE_FACET + "\",\"category\":\"" + TYPE_FACET_CATEGORY + "\"}]}";

    /** A search limited to the datasets a user's consents cover. */
    public static final String SEARCH_WITH_CONSENTS =
        "{\"search\":\"" + SEARCH_TERM + "\",\"consents\":[\"" + CONSENT + "\",\"" + SECOND_CONSENT + "\"]}";

    /** No restriction: counts or lists every concept. */
    public static final String EVERYTHING = "{}";

    private FilterExamples() {}
}
