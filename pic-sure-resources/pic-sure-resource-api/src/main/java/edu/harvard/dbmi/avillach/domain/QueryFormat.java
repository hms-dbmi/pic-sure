package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.harvard.dbmi.avillach.QueryFormatDeserializer;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@JsonDeserialize(using = QueryFormatDeserializer.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryFormat {
	private String name;
	private String description;
	private Map<String, JsonNode> specification;
	private List<Map<String,String>> examples;
	
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
	
	public Map<String, JsonNode> getSpecification() {
		return specification;
	}
	public QueryFormat setSpecification(Map<String, JsonNode> specification) {
		this.specification = specification;
		return this;
	}
	
	public List<Map<String,String>> getExamples() {
		return examples;
	}
	public QueryFormat setExamples(List<Map<String,String>> examples) {
		this.examples = examples;
		return this;
	}

}
