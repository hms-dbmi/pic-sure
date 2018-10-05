package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

@Entity(name = "user")
public class User extends BaseEntity implements Serializable{

	@Column(unique = true)
	private String userId;

	@Column(unique = true)
	private String subject;

	private String roles;

	private String email;

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

	public void setAuth0metadata(String auth0metadata) {
		this.auth0metadata = auth0metadata;
	}

	public String getGeneralMetadata() {
		return generalMetadata;
	}

	public void setGeneralMetadata(String generalMetadata) {
		this.generalMetadata = generalMetadata;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}
}
