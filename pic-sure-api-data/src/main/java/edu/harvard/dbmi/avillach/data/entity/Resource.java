package edu.harvard.dbmi.avillach.data.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.Column;
import javax.persistence.Entity;
import java.util.Objects;

@Entity(name = "resource")
public class Resource extends BaseEntity{

	private String name;
	private String description;
	private String baseUrl;

	@JsonIgnore
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

	public String getToken() {
		return token;
	}

	public Resource setToken(String token) {
		this.token = token;
		return this;
	}
}
