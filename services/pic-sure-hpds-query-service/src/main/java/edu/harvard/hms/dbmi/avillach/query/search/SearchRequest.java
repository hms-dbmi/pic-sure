package edu.harvard.hms.dbmi.avillach.query.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body of {@code POST /hpds/{backend}/search}: the search term under the {@code query} key. Members other than {@code query} are
 * ignored, so a body that still carries {@code resourceUUID} binds as before.
 *
 * @param query the term to match against concept paths, categorical values and variant annotation names, or null to list everything
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "A concept search term under the query key. Members other than query are ignored.")
public record SearchRequest(
    @Schema(
        description = "Text matched, ignoring case, against concept paths, categorical values and variant annotation names. "
            + "A null or absent term lists every concept.",
        example = "\\demographics\\AGE\\"
    ) String query
) {

    /**
     * Wraps the term in the envelope HPDS receives.
     *
     * @return a new envelope holding this request's search term
     */
    public GeneralQueryRequest toOutbound() {
        return new GeneralQueryRequest().setQuery(query);
    }
}
