package edu.harvard.hms.dbmi.avillach.hpds.data.query;

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
	 * Create a dataframe, but do not allow conventional access to it.
	 * Instead, the dataframe will be accessed in the backend only,
	 * where it is sent to a S3 bucket by a GIC admin.
	 *
	 */
	SECRET_ADMIN_DATAFRAME,
	/**
	 * Return one patient count for each concept path included in
	 * the crossCountFields
	 */
	CROSS_COUNT,
	/**
	 * Return multiple patient count for each concept and its given variables
	 * included in the categoryFilters field
	 */
	CATEGORICAL_CROSS_COUNT,
	/**
	 * Return one patient count for each concept path included in
	 * the numericFilters field
	 */
	CONTINUOUS_CROSS_COUNT,
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
	 * Return the number of observations for included patients and
	 * included fields, broken up across the included cross count fields.
	 */
	OBSERVATION_CROSS_COUNT,
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
	 * This returns quasi-VCF lines for the variants expressed in the
	 * query without patient data.
	 */
	AGGREGATE_VCF_EXCERPT,
	/**
	 * This exports data in the same format as the HPDS csv loader, which
	 * is suitable to time series analysis and/or loading into another 
	 * instance of HPDS.
	 */
	DATAFRAME_TIMESERIES,

	/**
	 * Count number of patients and number of available concept paths. Useful for federated networks
	 * where not every concept path in a query is guaranteed to be in a specific portal
	 */
	PATIENT_AND_CONCEPT_COUNT,
	/**
     * Exports data as PFB, using avro
     * <a href="https://uc-cdis.github.io/pypfb/">https://uc-cdis.github.io/pypfb/</a>
     */
	DATAFRAME_PFB,

	/**
	 * Patients associated with this query
	 */
	PATIENTS
}
