package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.*;

@Entity(name="userMetadataMapping")
public class UserMetadataMapping extends BaseEntity {

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(
			name = "connectionId",
			referencedColumnName = "id"
	)
	private Connection connection;

	private String generalMetadataJsonPath;
	
	private String auth0MetadataJsonPath;

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

	public Connection getConnection() {
		return connection;
	}

	public UserMetadataMapping setConnection(Connection connection) {
		this.connection = connection;
		return this;
	}
}
