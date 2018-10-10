package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.Column;
import javax.persistence.Entity;
import java.io.Serializable;

@Entity(name = "user")
public class User extends BaseEntity implements Serializable{

	@Column(unique = true)
	private String userId;

	@Column(unique = true)
	private String subject;

	private String roles;

	private String email;

	private String connectionId;

	private boolean matched;

	@Column(name = "auth0_metadata")
	private String auth0metadata;

	@Column(name = "general_metadata")
	private String generalMetadata;

	public String getUserId() {
		return userId;
	}

	public User setUserId(String userId) {
		this.userId = userId;
		return this;
	}

	public String getSubject() {
		return subject;
	}

	public User setSubject(String subject) {
		this.subject = subject;
		return this;
	}

	public String getRoles() {
		return roles;
	}

	public User setRoles(String roles) {
		this.roles = roles;
		return this;
	}

	public String getAuth0metadata() {
		return auth0metadata;
	}

	public User setAuth0metadata(String auth0metadata) {
		this.auth0metadata = auth0metadata;
		return this;
	}

	public String getGeneralMetadata() {
		return generalMetadata;
	}

	public User setGeneralMetadata(String generalMetadata) {
		this.generalMetadata = generalMetadata;
		return this;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getConnectionId() {
		return connectionId;
	}

	public User setConnectionId(String connectionId) {
		this.connectionId = connectionId;
		return this;
	}

	public boolean isMatched() {
		return matched;
	}

	public void setMatched(boolean matched) {
		this.matched = matched;
	}
}
