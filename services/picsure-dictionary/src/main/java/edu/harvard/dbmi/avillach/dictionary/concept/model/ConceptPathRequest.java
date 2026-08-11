package edu.harvard.dbmi.avillach.dictionary.concept.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of the concept lookups that are POSTs rather than GETs. A concept path is itself a slash-delimited string, so it cannot ride in
 * the URL without double-encoding every segment; it rides in a body instead. Modelled as a record so the field is named and documented on
 * the wire instead of being an anonymous raw string.
 */
@Schema(description = "A single concept path to look up")
public record ConceptPathRequest(@Schema(description = "Concept path; POSTed because paths contain slashes") String conceptPath) {
}
