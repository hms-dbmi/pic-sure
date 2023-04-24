package edu.harvard.dbmi.avillach.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A search results object")
public class SearchResults {

	@Schema(description = "The results of the search.")
	Object results;

	@Schema(description = "The query that was used to generate the results.")
	String searchQuery;

	public Object getResults() {
		return results;
	}

	public SearchResults setResults(Object results) {
		this.results = results;
		return this;
	}

	public String getSearchQuery() {
		return searchQuery;
	}

	public SearchResults setSearchQuery(String searchQuery) {
		this.searchQuery = searchQuery;
		return this;
	}
}
