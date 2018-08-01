package edu.harvard.dbmi.avillach.data.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.persistence.Column;
import javax.persistence.Entity;

@Entity(name = "resource")
public class Resource extends BaseEntity{

	private String name;
	@Column(length = 8192)
	private String description;
	private String baseUrl;

	@Column(length = 8192)
	private String token;
	
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
	
	public String getBaseUrl() {
		return baseUrl;
	}
	public Resource setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
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
}
