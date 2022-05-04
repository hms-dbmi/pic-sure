package edu.harvard.dbmi.avillach.data.entity;


/*
 * This class gets created as part of the pic-sure DB schema, but no objects of this type are ever persisted.
 * Its use is to mirror the User object from the auth DB to maintain schema separation. - nc 
 * 
 */
public class User extends BaseEntity {

	private String userId;

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
