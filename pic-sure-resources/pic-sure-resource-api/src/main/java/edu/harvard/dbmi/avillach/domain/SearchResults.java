package edu.harvard.dbmi.avillach.domain;

public class SearchResults {
	Object results;
	
	String searchQuery;

	public Object getResults() {
		return results;
	}

	public void setResults(Object results) {
		this.results = results;
	}

	public String getSearchQuery() {
		return searchQuery;
	}

	public void setSearchQuery(String searchQuery) {
		this.searchQuery = searchQuery;
	}
}
