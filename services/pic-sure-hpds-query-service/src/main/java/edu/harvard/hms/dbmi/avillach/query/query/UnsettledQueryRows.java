package edu.harvard.hms.dbmi.avillach.query.query;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The ids of owned query rows that may not describe the job HPDS holds under their id.
 *
 * <p>A row describes its job as long as every submit to HPDS is followed by a save of the row with the consents the submit ran under. The
 * two come apart when HPDS acts on a submit and the save after it fails, or when the answer to a submit is lost after HPDS acted on it.
 * {@code QueryService} therefore marks the id of a stored row here immediately before it sends the row's query to HPDS and removes the mark
 * only once the row is saved. While a row id is marked, the consents and the status the row records say nothing about the job HPDS holds. A
 * request for the row must not compare its caller's consents with the recorded ones and serve or reuse what HPDS holds. It runs the query
 * again under the consents it just fetched and saves the row, which removes the mark.
 *
 * <p>Only an id that already has a stored row is ever marked. A first submit, which finds no row under its id, marks nothing, because a
 * mark is read only for a row that was loaded and an id without a row has no recorded consents to mistrust. This also bounds the set by the
 * number of stored rows, where a mark per failed first submit would let a caller add one for every distinct query.
 *
 * <p>A mark is read and changed only by a thread that holds the {@link QueryRowLocks} lock of that row, so a request never sees the mark of
 * a submit that is still on its way to its save.
 *
 * <p>The marks live in the memory of this process and are lost when it stops. A row whose save failed is no longer known to disagree with
 * HPDS once this service has restarted, and a second instance of this service would not see the marks of the first. Both are the same
 * accepted gap as a process that stops between the HPDS submit and the save: the row and HPDS then disagree until the next consent change,
 * the next explicit resubmit of a failed row, or an HPDS restart.
 */
@Component
public class UnsettledQueryRows {

    private final Set<UUID> unsettledRowIds = ConcurrentHashMap.newKeySet();

    /**
     * Marks a row as one that may not describe the job HPDS holds.
     *
     * @param rowId the id of the row, which is also the id HPDS stores its job under
     * @return true when this call added the mark, false when the row was marked already
     * @throws NullPointerException when the row id is null
     */
    public boolean markUnsettled(UUID rowId) {
        return unsettledRowIds.add(Objects.requireNonNull(rowId, "rowId is null"));
    }

    /**
     * Removes the mark of a row. Does nothing for a row that is not marked.
     *
     * @param rowId the id of the row
     * @throws NullPointerException when the row id is null
     */
    public void markSettled(UUID rowId) {
        unsettledRowIds.remove(Objects.requireNonNull(rowId, "rowId is null"));
    }

    /**
     * Tells whether a row is marked.
     *
     * @param rowId the id of the row
     * @return true when the row may not describe the job HPDS holds
     * @throws NullPointerException when the row id is null
     */
    public boolean isUnsettled(UUID rowId) {
        return unsettledRowIds.contains(Objects.requireNonNull(rowId, "rowId is null"));
    }
}
