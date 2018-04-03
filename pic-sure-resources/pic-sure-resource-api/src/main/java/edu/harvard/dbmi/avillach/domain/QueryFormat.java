package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.harvard.dbmi.avillach.QueryFormatDeserializer;

import java.io.Serializable;

@JsonDeserialize(using = QueryFormatDeserializer.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryFormat {
	private String name;
	private String description;
	private Serializable specification;
	private Serializable[] examples;
	
	public String getName() {
		return name;
	}
	public QueryFormat setName(String name) {
		this.name = name;
		return this;
	}
	
	public String getDescription() {
		return description;
	}
	public QueryFormat setDescription(String description) {
		this.description = description;
		return this;
	}
	
	public Serializable getSpecification() {
		return specification;
	}
	public QueryFormat setSpecification(Serializable specification) {
		this.specification = specification;
		return this;
	}
	
	public Serializable[] getExamples() {
		return examples;
	}
	public QueryFormat setExamples(Serializable[] examples) {
		this.examples = examples;
		return this;
	}

}
