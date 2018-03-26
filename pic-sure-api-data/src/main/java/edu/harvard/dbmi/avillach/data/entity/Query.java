package edu.harvard.dbmi.avillach.data.entity;

import java.sql.Date;

import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;

@Entity
public class Query extends BaseEntity {

	//TODO may not need these two things
	private Date startTime;
	
	private Date readyTime;

	//Resource is responsible for mapping internal status to picsurestatus
	private PicSureStatus status;

	private String resourceResultId;

	@ManyToOne
	@JoinColumn(name = "resourceId")
	private Resource resource;

	public Resource getResource() {
		return resource;
	}

    public void setResource(Resource resource) {
        this.resource = resource;
    }

    public String getResourceResultId() {
		return resourceResultId;
	}

	public void setResourceResultId(String resourceResultId) {
		this.resourceResultId = resourceResultId;
	}
}
