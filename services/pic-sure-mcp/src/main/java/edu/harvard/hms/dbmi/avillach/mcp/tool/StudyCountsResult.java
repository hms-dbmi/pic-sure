package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * Exact participant counts per study, as the signed-in user may see them.
 *
 * @param basis how the studies were found: {@link #STUDY_CONSENTS} or {@link #DICTIONARY_SAMPLE}
 * @param studies one entry per study
 * @param totalParticipants every participant the user may see, or null when it could not be counted
 * @param matchesTotal for {@link #DICTIONARY_SAMPLE}, whether the study counts add up to {@code totalParticipants}
 * @param note what the counts mean and how far to trust them
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StudyCountsResult(
    @Schema(description = "study_consents when the data has study consent concepts, otherwise dictionary_sample") String basis,
    @Schema(description = "One entry per study") List<Study> studies,
    @Schema(requiredMode = NOT_REQUIRED, description = "Every participant the user may see") Integer totalParticipants,
    @Schema(
        requiredMode = NOT_REQUIRED, description = "For dictionary_sample, whether the study counts add up to totalParticipants"
    ) Boolean matchesTotal, @Schema(requiredMode = NOT_REQUIRED, description = "What the counts mean and how far to trust them") String note
) {

    /** Counts by study consent concept, the way BDC-style data is organised. */
    public static final String STUDY_CONSENTS = "study_consents";

    /** Counts by dataset, each from the most-populated variable sampled from that dataset. */
    public static final String DICTIONARY_SAMPLE = "dictionary_sample";

    /**
     * One study.
     *
     * @param name the dataset name, or the study consent concept path
     * @param count the exact count, or null when there is none
     * @param noData true when the user has no participants with data in this study
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Study(
        @Schema(description = "The dataset name, or the study consent concept path") String name,
        @Schema(requiredMode = NOT_REQUIRED, description = "The exact count") Integer count,
        @Schema(requiredMode = NOT_REQUIRED, description = "True when the user has no participants with data in this study") Boolean noData
    ) {
    }
}
