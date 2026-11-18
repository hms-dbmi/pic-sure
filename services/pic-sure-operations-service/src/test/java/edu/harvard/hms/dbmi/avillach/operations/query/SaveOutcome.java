package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * How one save in a race ended: the id it returned, or the failure it threw.
 *
 * @param saved the id the save returned, or null when it failed
 * @param failure what the save threw, or null when it returned
 */
record SaveOutcome(UUID saved, Throwable failure) {

    /**
     * Runs a save and records how it ended instead of throwing.
     *
     * @param save the save to run
     * @return the outcome
     */
    static SaveOutcome of(Callable<UUID> save) {
        try {
            return new SaveOutcome(save.call(), null);
        } catch (Exception e) {
            return new SaveOutcome(null, e);
        }
    }
}
