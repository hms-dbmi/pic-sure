package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of a {@code POST /open/validate} answer. A caller that sends {@code "responseVersion": 2} receives an
 * {@link OpenAccessValidationResponse}; every other caller receives a {@link Legacy} verdict, which serialises as a bare JSON boolean so a
 * gateway that predates the object response keeps reading it.
 */
@Schema(
    description = "The answer to an open-access validate: a bare boolean, or an OpenAccessValidationResponse when the request asked for "
        + "responseVersion 2."
)
public sealed interface OpenAccessVerdict permits OpenAccessValidationResponse, OpenAccessVerdict.Legacy {

    /**
     * The verdict for a caller that did not ask for {@code responseVersion} 2. Serialises as the bare boolean {@code valid}.
     *
     * @param valid whether the request is granted
     */
    @Schema(description = "The answer to an open-access validate that did not ask for responseVersion 2: a bare JSON boolean.")
    record Legacy(@Schema(description = "Whether the request is granted.", requiredMode = Schema.RequiredMode.REQUIRED) boolean valid)
        implements OpenAccessVerdict {

        @JsonValue
        @Override
        public boolean valid() {
            return valid;
        }
    }
}
