package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;

/** A documented polymorphic type. The walk reaches each subtype the annotation names. */
@Schema(description = "A shape drawn on a chart")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = Circle.class, name = "circle"), @JsonSubTypes.Type(value = Blob.class, name = "blob")})
public sealed interface Shape permits Circle, Blob, Sketch {}
