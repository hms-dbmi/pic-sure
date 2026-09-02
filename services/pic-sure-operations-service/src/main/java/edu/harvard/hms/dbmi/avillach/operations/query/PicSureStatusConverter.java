package edu.harvard.hms.dbmi.avillach.operations.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@code query.status} to {@link PicSureStatus} by ordinal, the encoding every row in the table already uses, reading an ordinal the
 * enum does not define as {@code null} instead of throwing.
 *
 * <p>{@code @Enumerated(EnumType.ORDINAL)} cannot do that: Hibernate's {@code EnumJavaType.fromByte} indexes the constant array with no
 * range check, so a single unmapped row fails the whole request. The deployed table holds such rows -- two legacy branches each shipped a
 * fifth constant that never reached main (FEDERATED in 2023-10, NOT_FOUND in 2026-05), so a stored 4 decodes to a different status in each
 * history and no constant added here could resolve which.
 *
 * <p>Writes still emit the ordinal into the same {@code int(11)} column, so the stored encoding is unchanged in both directions.
 */
@Converter
public class PicSureStatusConverter implements AttributeConverter<PicSureStatus, Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(PicSureStatusConverter.class);

    private static final PicSureStatus[] BY_ORDINAL = PicSureStatus.values();

    @Override
    public Integer convertToDatabaseColumn(PicSureStatus status) {
        return status == null ? null : status.ordinal();
    }

    @Override
    public PicSureStatus convertToEntityAttribute(Integer ordinal) {
        if (ordinal == null) {
            return null;
        }
        if (ordinal < 0 || ordinal >= BY_ORDINAL.length) {
            LOG.warn("query.status holds ordinal {}, which PicSureStatus does not define; reading it as null", ordinal);
            return null;
        }
        return BY_ORDINAL[ordinal];
    }
}
