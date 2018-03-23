package edu.harvard.dbmi.avillach.data.entity;

import java.sql.Date;

import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;

@Entity
public class Query extends BaseEntity {
	
	private Date startTime;
	
	private Date readyTime;
	
	private PicSureStatus status;

	@ManyToOne
	@JoinColumn(name = "resourceId")
	private Resource resource;

	public Resource getResource() {
		return resource;
	}
}
