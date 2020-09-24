package edu.harvard.hms.dbmi.avillach;

public enum ResultType {
	/**
	 * Just a patient count
	 */
	COUNT,
	/**
	 * Return a CSV with an observation for each concept for each
	 * patient included in the query. If there are multiple facts
	 * for a specific patient, you will get the one that happens
	 * to resolve from a binary search of the facts for the patient
	 * id.
	 */
	DATAFRAME,
	/**
	 * Return one patient count for each concept path included in
	 * the crossCountFields
	 */
	CROSS_COUNT,
	/**
	 * Return all variant info column metadata
	 */
	INFO_COLUMN_LISTING,
	/**
	 * Return the number of total observations for included patients and
	 * included fields.
	 */
	OBSERVATION_COUNT,
	/**
	 * This was developed for UDN, but is completely useless and should
	 * be deleted.
	 */
	DATAFRAME_MERGED,
	/**
	 * Not completely implemented and currently dead code. Someone with
	 * statistics experience needs to develop a p-value based filter for
	 * the subset of patients.
	 */
	VARIANTS_OF_INTEREST,
	/**
	 * The count is the size of the intersection of VariantSpecs that is
	 * the result of applying all INFO filters in the query.
	 * 
	 * This is used by clients to limit queries to reasonable numbers of
	 * variants.
	 */
	VARIANT_COUNT_FOR_QUERY,
	/**
	 * This returns the list of string representations of VariantSpecs
	 * involved in a query.
	 */
	VARIANT_LIST_FOR_QUERY,
	/**
	 * This returns quasi-VCF lines for the variants expressed in the
	 * query.
	 */
	VCF_EXCERPT,
	/**
	 * This returns data to feed a timeline,
	 * TODO: add more details later.
	 */
	TIMELINE_DATA
}
