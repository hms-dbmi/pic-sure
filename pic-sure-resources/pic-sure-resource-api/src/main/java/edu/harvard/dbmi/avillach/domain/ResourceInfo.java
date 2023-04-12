package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.util.List;
import java.util.UUID;

/**
 * Information about interacting with a specific resource.
 * 
 */
@ApiModel(description = "Information about interacting with a specific resource.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResourceInfo {

	@ApiModelProperty(value = "The UUID of the resource.", required = true)
	private UUID id;

	@ApiModelProperty(value = "The name of the resource.", required = true)
	private String name;

	@ApiModelProperty(value = "The query formats supported by the resource.", required = true)
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
