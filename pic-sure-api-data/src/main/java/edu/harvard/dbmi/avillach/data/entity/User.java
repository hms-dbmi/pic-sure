package edu.harvard.dbmi.avillach.data.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.UniqueConstraint;

@Entity(name = "user")
public class User extends BaseEntity {

	@Column(unique = true)
	private String userId;

	@Column(unique = true)
	private String subject;
	
	private String roles;
	
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
}
