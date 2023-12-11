package edu.harvard.dbmi.avillach.data.entity;

import java.io.StringReader;
import java.util.Optional;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.persistence.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

@Entity(name = "resource")
public class Resource extends BaseEntity{

	private String name;
	@Column(length = 8192)
	private String description;
	private String targetURL;


	@Convert(converter = ResourcePathConverter.class)
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
		JsonObject metadataObj = null;
		if(metadata != null) {
			JsonReader jsonReader = Json.createReader(new StringReader(metadata));
			metadataObj = jsonReader.readObject();
			jsonReader.close();
		}
		return Json.createObjectBuilder()
	            .add("uuid", uuid.toString())
	            .add("name", name)
	            .add("description", description)
	            .add("hidden", Boolean.toString(hidden))
	            .add("metadata", metadataObj)
	            .build().toString();
	}

	/**
	 * This resource path converter allows resource paths to contain a reference to a specific stack that is
	 * imputed at runtime based an an environment parameter. This allows multiple stacks to share the same database.
	 *
	 * The ___target_stack___ token in any resource path value will be replaced with the TARGET_STACK environment variable
	 */
	@Converter
	public static class ResourcePathConverter implements AttributeConverter<String, String> {

		public ResourcePathConverter() {
		}

		private static final Optional<String> targetStack = Optional.ofNullable(System.getProperty("TARGET_STACK", null));

		@Override
		public String convertToDatabaseColumn(String attribute) {
			return attribute;
		}

		@Override
		public String convertToEntityAttribute(String dbData) {
			return targetStack
					.map(stack -> dbData.replace("___target_stack___", stack))
					.orElse(dbData);
		}
	}
}
