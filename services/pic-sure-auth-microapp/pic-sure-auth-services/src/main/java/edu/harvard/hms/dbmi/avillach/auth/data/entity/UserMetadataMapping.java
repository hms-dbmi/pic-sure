package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.Entity;

@Entity(name="userMetadataMapping")
public class UserMetadataMapping extends BaseEntity {
	
	private String connectionId;
	
	private String generalMetadataJsonPath;
	
	private String auth0MetadataJsonPath;

	public String getConnectionId() {
		return connectionId;
	}

	public UserMetadataMapping setConnectionId(String connectionId) {
		this.connectionId = connectionId;
		return this;
	}

	public String getGeneralMetadataJsonPath() {
		return generalMetadataJsonPath;
	}

	public UserMetadataMapping setGeneralMetadataJsonPath(String generalMetadataJsonPath) {
		this.generalMetadataJsonPath = generalMetadataJsonPath;
		return this;
	}

	public String getAuth0MetadataJsonPath() {
		return auth0MetadataJsonPath;
	}

	public UserMetadataMapping setAuth0MetadataJsonPath(String auth0MetadataJsonPath) {
		this.auth0MetadataJsonPath = auth0MetadataJsonPath;
		return this;
	}
	
}
