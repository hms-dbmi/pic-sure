package edu.harvard.dbmi.avillach.domain;

public class SearchResults {
	Object results;
	
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
