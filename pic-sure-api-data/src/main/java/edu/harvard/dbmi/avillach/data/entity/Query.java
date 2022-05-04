package edu.harvard.dbmi.avillach.data.entity;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Date;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;

import edu.harvard.dbmi.avillach.util.PicSureStatus;

@Entity(name = "query")
public class Query extends BaseEntity {
	
	/**
	 * Override the base repo UUID field, so we don't use the auto-generated IDs
	 */
	protected UUID uuid;

	//TODO may not need these two things
	private Date startTime;
	
	private Date readyTime;

	//Resource is responsible for mapping internal status to picsurestatus
	private PicSureStatus status;

	private String resourceResultId;
	

	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	//Original query request
	@Lob
    @Column(columnDefinition="BLOB")
	private byte[] query;

	@ManyToOne
	@JoinColumn(name = "resourceId")
	private Resource resource;

	@Column(length = 8192)
	private byte[] metadata;

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

	public void setStartTime(Date startTime) {
		this.startTime = startTime;
	}

	public Date getStartTime() {
		return startTime;
	}

	public Date getReadyTime() {
		return readyTime;
	}

	public PicSureStatus getStatus() {
		return status;
	}

	public void setReadyTime(Date readyTime) {
		this.readyTime = readyTime;
	}

	public void setStatus(PicSureStatus status) {
		this.status = status;
	}

	public String getQuery() {
		
		if (this.query == null || this.query.length == 0) {
            return "";
        }
		
		String outStr = "";
		try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(this.query));
	        BufferedReader bf = new BufferedReader(new InputStreamReader(gis, "UTF-8"));){
	        
	        String line;
	        while ((line=bf.readLine())!=null) {
	          outStr += line;
	        }
		} catch (IOException e) {
			e.printStackTrace();
		}
        return outStr;
	}

	public void setQuery(String queryStr) {
		if (queryStr == null || queryStr.length() == 0) {
			this.query = new byte[0];
            return;
        }
       
		try (ByteArrayOutputStream obj=new ByteArrayOutputStream();
			GZIPOutputStream gzip = new GZIPOutputStream(obj);){
			gzip.write(queryStr.getBytes("UTF-8"));
	        gzip.close();
	        this.query = obj.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public byte[] getMetadata() {
		return metadata;
	}

	public void setMetadata(byte[] metadata) {
		this.metadata = metadata;
	}
}
