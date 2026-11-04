package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalLong;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Described members whose JDK value types sit outside the common scalars, none with an example, and an
 * optional nested model that needs none.
 *
 * @param amount a decimal
 * @param scheduled a local date and time
 * @param timeout a duration
 * @param homepage a URI
 * @param nickname an optional string
 * @param size an optional long
 * @param deputy an optional nested model
 */
@Schema(description = "A model whose less common values carry no example")
public record MissingValueExamples(
    @Schema(description = "An amount") BigDecimal amount,
    @Schema(description = "A scheduled time") LocalDateTime scheduled,
    @Schema(description = "A timeout") Duration timeout,
    @Schema(description = "A homepage") URI homepage,
    @Schema(description = "A nickname") Optional<String> nickname,
    @Schema(description = "A size") OptionalLong size,
    @Schema(description = "A deputy") Optional<Investigator> deputy
) {}
