package edu.harvard.hms.dbmi.avillach.auth.model.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code POST /studyAccess}, which previously bound a raw {@code String} under {@code consumes = application/json} -- a shape
 * no schema can describe and that only works if the caller happens to send a bare JSON string literal.
 *
 * <p>This is a breaking change to that endpoint's request shape: callers must now send {@code {"studyIdentifier": "phs000001.c1"}} instead
 * of {@code "phs000001.c1"}. It is SUPER_ADMIN/ADMIN-only tooling, which is why the break is taken here rather than deferred.
 */
@Schema(description = "The study to create the manual role, privileges, and access rules for")
public record StudyAccessRequest(
    @Schema(description = "Study identifier from the FENCE metadata mapping", example = "phs000001.c1") String studyIdentifier
) {
}
