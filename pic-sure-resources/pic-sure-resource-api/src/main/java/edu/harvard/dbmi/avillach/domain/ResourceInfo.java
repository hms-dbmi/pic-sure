package edu.harvard.dbmi.avillach.domain;

import java.util.List;
import java.util.UUID;

public class ResourceInfo {
	private UUID id;
	private String name;
	private List<QueryFormat> queryFormats;
	public UUID getId() {
		return id;
	}
	public ResourceInfo setId(UUID id) {
		this.id = id;
		return this;
	}
	public String getName() {
		return name;
	}
	public ResourceInfo setName(String name) {
		this.name = name;
		return this;
	}
	public List<QueryFormat> getQueryFormats() {
		return queryFormats;
	}
	public ResourceInfo setQueryFormats(List<QueryFormat> queryFormats) {
		this.queryFormats = queryFormats;
		return this;
	}
	
}
