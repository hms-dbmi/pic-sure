package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.UUID;

/**
 * The columns of a {@link Query} row that say which variant of which general query it is. {@link QueryRepository#findVariantsOf} selects
 * these columns alone, so listing the variants of a general query never loads or decompresses a stored query body.
 *
 * @param queryToVariantUuid the id of the general query the row is a variant of
 * @param uuid the id of the query row
 * @param sample whether the query returns a sample of the matching patients
 * @param resultType the result type name stored on the row, or null when none was stored
 */
public record QueryVariantRow(UUID queryToVariantUuid, UUID uuid, boolean sample, String resultType) {
}
