package edu.harvard.dbmi.avillach.data.entity;

import javax.json.Json;
import javax.persistence.Column;
import javax.persistence.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

@Entity(name = "resource")
public class Resource extends BaseEntity{

	private String name;
	@Column(length = 8192)
	private String description;
	private String targetURL;
	private String resourceRSPath;

	@Column(length = 8192)
	private String token;
	
	private Boolean hidden;
	
	private String metadata;
	
	public String getName() {
		return name;
	}
	public Resource setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}
	public Resource setDescription(String description) {
		this.description = description;
		return this;
	}
	
	public String getTargetURL() {
		return targetURL;
	}
	public Resource setTargetURL(String targetURL) {
		this.targetURL = targetURL;
		return this;
	}

	public String getResourceRSPath() {
		return resourceRSPath;
	}

	public Resource setResourceRSPath(String resourceRSPath) {
		this.resourceRSPath = resourceRSPath;
		return this;
	}

	@JsonIgnore
	public String getToken() {
		return token;
	}

	@JsonProperty
	public Resource setToken(String token) {
		this.token = token;
		return this;
	}
	
	//visible (not hidden) by default 
	public Boolean getHidden() {
		return hidden == null ? Boolean.FALSE : hidden;
	}
	public void setHidden(Boolean hidden) {
		this.hidden = hidden;
	}
	
	public String getMetadata() {
		return metadata;
	}
	public void setMetadata(String metadata) {
		this.metadata = metadata;
	}
	
	@Override
	public String toString() {
		return Json.createObjectBuilder()
	            .add("uuid", uuid.toString())
	            .add("name", name)
	            .add("description", description)
	            .add("hidden", Boolean.toString(hidden))
	            .add("metadata", metadata)
	            .build().toString();
	}
}
