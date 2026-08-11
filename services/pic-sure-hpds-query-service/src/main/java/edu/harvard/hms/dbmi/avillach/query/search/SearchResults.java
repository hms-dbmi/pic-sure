package edu.harvard.hms.dbmi.avillach.query.search;


/**
 * HPDS's dictionary-search payload as this service sees it: {@code {"results": ..., "searchQuery": "..."}}. Byte-identical to the retired
 * {@code pic-sure-api-model} {@code SearchResults} it replaces, so the search ingress and the aggregate study-consents lookup keep the
 * exact response bodies they had.
 *
 * <p><b>Local by design.</b> {@code results} is HPDS's own dictionary shape -- a map keyed by {@code "phenotypes"}/{@code "info"} whose
 * values are HPDS metadata objects. This service is a passthrough for it: {@link SearchService} hands it straight back to the caller and
 * {@code AggregateService} reaches into it for nothing but the phenotype key set. Putting an {@code Object}-typed payload into
 * {@code pic-sure-contracts} would weaken the shared module for a shape only HPDS defines, so HPDS keeps its own mirror of this record and
 * the two are pinned against each other by {@code ResourceWebClientTest} on this side.
 */
public record SearchResults(Object results, String searchQuery) {
}
