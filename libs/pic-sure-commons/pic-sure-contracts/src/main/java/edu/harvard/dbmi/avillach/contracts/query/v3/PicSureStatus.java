package edu.harvard.dbmi.avillach.contracts.query.v3;


/**
 * PIC-SURE-wide status of a query, normalized across every backing resource.
 *
 * <p>Nothing persists or transmits the ordinal any more: the wire carries the enum NAME, and the query store persists the NAME
 * ({@code @Enumerated(EnumType.STRING)} on operations-service's {@code Query} entity). The declaration order is nonetheless still
 * load-bearing for one reason -- rows written before that flip hold the OLD ordinal, and {@code V9__ALTER_QUERY_STATUS_TO_STRING.sql}
 * translates them with a fixed {@code CASE} mapping ({@code 0 -> QUEUED, 1 -> PENDING, 2 -> ERROR, 3 -> AVAILABLE}). Reordering or
 * inserting a constant here would put that mapping, and so every legacy row it has yet to convert, out of step with this enum.
 */
public enum PicSureStatus {
    QUEUED, PENDING, ERROR, AVAILABLE
}
