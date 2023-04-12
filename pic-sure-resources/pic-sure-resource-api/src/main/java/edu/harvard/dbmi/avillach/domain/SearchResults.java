package edu.harvard.dbmi.avillach.domain;

import io.swagger.annotations.ApiModelProperty;

public class SearchResults {

	@ApiModelProperty(value = "The results of the search.", required = true)
	Object results;

	@ApiModelProperty(value = "The query that was used to generate the results.", required = true)
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
