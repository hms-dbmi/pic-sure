package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Described collections and arrays of scalars with no example, next to collections whose elements are exempt.
 *
 * @param names a list of strings
 * @param ids a set of UUIDs
 * @param totals a collection of boxed numbers
 * @param aliases an array of strings
 * @param counts an array of primitives
 * @param matrix a list of lists of strings
 * @param flags a list of booleans, exempt
 * @param kinds a list of enums, exempt
 */
@Schema(description = "A model whose scalar collections carry no example")
public record MissingCollectionExamples(
    @Schema(description = "Names") List<String> names,
    @Schema(description = "Identifiers") Set<UUID> ids,
    @Schema(description = "Totals") Collection<Integer> totals,
    @Schema(description = "Aliases") String[] aliases,
    @Schema(description = "Counts") int[] counts,
    @Schema(description = "Rows of names") List<List<String>> matrix,
    @Schema(description = "Flags") List<Boolean> flags,
    @Schema(description = "Kinds") List<StudyKind> kinds
) {}
