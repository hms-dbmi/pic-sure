package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.*;
import java.io.Serializable;
import java.security.Principal;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Entity(name = "user")
public class User extends BaseEntity implements Serializable, Principal {

	@Column(unique = true)
	private String subject;

	@ManyToMany(fetch = FetchType.EAGER)
	@JoinTable(name = "user_role",
			joinColumns = {@JoinColumn(name = "user_id", nullable = false, updatable = false)},
			inverseJoinColumns = {@JoinColumn(name = "role_id", nullable = false, updatable = false)})
	private Set<Role> roles;

	private String email;

	@ManyToOne
	@JoinColumn(name = "connectionId")
	private Connection connection;

	private boolean matched;

	private Date acceptedTOS;

	@Column(name = "auth0_metadata")
	private String auth0metadata;

	@Column(name = "general_metadata")
	private String generalMetadata;

	public String getSubject() {
		return subject;
	}

	public User setSubject(String subject) {
		this.subject = subject;
		return this;
	}

	public Set<Role> getRoles() {
		return roles;
	}

	public User setRoles(Set<Role> roles) {
		this.roles = roles;
		return this;
	}

	/**
	 * return all privileges in the roles as a set
	 * @return
	 */
	@JsonIgnore
	public Set<Privilege> getTotalPrivilege(){
		if (roles == null)
			return null;

		Set<Privilege> privileges = new HashSet<>();
		roles.stream().forEach(r -> privileges.addAll(r.getPrivileges()));
		return privileges;
	}

	/**
	 * return all privilege name in each role as a set.
	 *
	 * @return
	 */
	@JsonIgnore
	public Set<String> getPrivilegeNameSet(){
		Set<Privilege> totalPrivilegeSet = getTotalPrivilege();

		if (totalPrivilegeSet == null)
			return null;

		Set<String> nameSet = new HashSet<>();
		totalPrivilegeSet.stream().forEach(p -> nameSet.add(p.getName()));
		return nameSet;
	}

	/**
	 * return privilege names in each role as a set based on Application given.
	 *
	 * @return
	 */
	@JsonIgnore
	public Set<String> getPrivilegeNameSetByApplication(Application application){
		Set<Privilege> totalPrivilegeSet = getTotalPrivilege();

		if (totalPrivilegeSet == null)
			return null;

		Set<String> nameSet = new HashSet<>();
		if (application == null)
			return nameSet;

		totalPrivilegeSet.stream().forEach(
				p -> {
					if (application.equals(p.getApplication()))
						nameSet.add(p.getName());
				}
		);
		return nameSet;
	}

	@JsonIgnore
	public String getPrivilegeString(){
		Set<Privilege> totalPrivilegeSet = getTotalPrivilege();

		if (totalPrivilegeSet == null)
			return null;

		return totalPrivilegeSet.stream().map(p -> p.getName()).collect(Collectors.joining(","));
	}

	@JsonIgnore
	public String getRoleString(){
		return (roles==null)?null:roles.stream().map(r -> r.name)
				.collect(Collectors.joining(","));
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

	public Connection getConnection() {
		return connection;
	}

	public User setConnection(Connection connection) {
		this.connection = connection;
		return this;
	}

	public boolean isMatched() {
		return matched;
	}

	public void setMatched(boolean matched) {
		this.matched = matched;
	}

	public Date getAcceptedTOS() {
		return acceptedTOS;
	}

	public void setAcceptedTOS(Date acceptedTOS) {
		this.acceptedTOS = acceptedTOS;
	}

	@Override
	public String getName() {
		return this.subject;
	}

	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	public static class UserForDisaply {
		String uuid;
		String email;
		Set<String> privileges;

		public UserForDisaply() {
		}

		public String getEmail() {
			return email;
		}

		public UserForDisaply setEmail(String email) {
			this.email = email;
			return this;
		}

		public Set<String> getPrivileges() {
			return privileges;
		}

		public UserForDisaply setPrivileges(Set<String> privileges) {
			this.privileges = privileges;
			return this;
		}

		public String getUuid() {
			return uuid;
		}

		public UserForDisaply setUuid(String uuid) {
			this.uuid = uuid;
			return this;
		}
	}
}
