package edu.harvard.dbmi.avillach.data.entity;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Schema(description = "A site that contains a PIC-SURE installation that we can send data to")
@Table(uniqueConstraints = {
    @UniqueConstraint(name = "unique_code", columnNames = { "code" }),
    @UniqueConstraint(name = "unique_email", columnNames = { "domain" })
})
@Entity(name = "site")
public class Site extends BaseEntity {

    @Schema(description = "The site code. Ex: BCH")
    @Column(length = 15)
    private String code;

    @Schema(description = "The site name. Ex: Boston Children's")
    @Column(length = 255)
    private String name;

    @Schema(description = "The email domain of users for this site. Ex: childrens.harvard.edu")
    @Column(length = 255)
    private String domain;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }
}
