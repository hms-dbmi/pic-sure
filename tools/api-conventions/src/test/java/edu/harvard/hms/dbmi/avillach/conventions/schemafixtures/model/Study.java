package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.library.SharedStatus;

/**
 * A fully documented record. It holds every scalar kind with an example, every kind of member that is exempt
 * from the example, a component Jackson ignores, and a static constant.
 */
@Schema(description = "A study a researcher can query")
public record Study(
    @Schema(description = "The study accession", example = "phs000007") String accession,
    @Schema(description = "How many participants the study enrolled", example = "1234") int participants,
    @Schema(description = "How many observations the study holds", example = "1790777") Long observations,
    @Schema(description = "The identifier of the study record", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6") UUID id,
    @Schema(description = "When the study was last loaded", example = "2026-09-30T14:05:00Z") Instant loaded,
    @Schema(description = "When the study record was last edited", example = "2026-09-30T14:05:00Z") Date edited,
    @Schema(description = "The day the study was released", example = "2026-09-30") LocalDate released,
    @Schema(description = "The consent groups the study defines", example = "[\"phs000007.c1\", \"phs000007.c2\"]") List<String> consents,
    @Schema(description = "Other names the study is known by", example = "[\"FHS\"]") String[] aliases,
    @Schema(description = "Whether every researcher may query the study") boolean open,
    @Schema(description = "Whether the study is harmonized, absent when nobody has decided") Boolean harmonized,
    @Schema(description = "The kind of data the study holds") StudyKind kind,
    @Schema(description = "The investigator who leads the study") Investigator lead,
    @Schema(description = "Every investigator on the study") List<Investigator> investigators,
    @Schema(description = "Free-form attributes keyed by attribute name, such as sponsor") Map<String, String> attributes,
    @Schema(description = "The load status, shared with other services") SharedStatus status,
    @JsonIgnore String loaderNote
) {

    public static final String DEFAULT_ACCESSION = "phs000007";
}
