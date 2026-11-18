package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/** What must hold after two writers raced on one query row. The H2 and the MySQL tests check the same outcomes through these. */
final class ConcurrentSaveAssertions {

    private ConcurrentSaveAssertions() {}

    /**
     * Checks that of two first saves under different owners one was stored and the other refused with 409, and that the row and its only
     * run are the winner's.
     *
     * @param picsureId the id both saves stored under
     * @param requests the two requests, in the order their outcomes are listed
     * @param outcomes how each save ended
     * @param stored the row as read after the race
     * @param runs the runs of the row after the race, oldest first
     */
    static void assertOneOwnerWon(
        UUID picsureId, List<SaveQueryRequest> requests, List<SaveOutcome> outcomes, StoredQuery stored, List<QueryRun> runs
    ) {
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null).hasSize(1);
        int winner = outcomes.get(0).failure() == null ? 0 : 1;
        assertThat(outcomes.get(winner).saved()).isEqualTo(picsureId);
        assertThat(outcomes.get(1 - winner).failure()).isInstanceOfSatisfying(PicsureException.class, refusal -> {
            assertThat(refusal.getStatus().value()).isEqualTo(409);
            assertThat(refusal.getErrorType()).isEqualTo("conflict");
            assertThat(refusal).hasMessage("Query " + picsureId + " belongs to a different owner");
        });
        assertThat(stored.userUuid()).isEqualTo(requests.get(winner).userUuid());
        assertThat(stored.consents()).containsExactlyEntriesOf(requests.get(winner).consents());
        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.getConsents()).containsExactlyEntriesOf(requests.get(winner).consents());
            assertThat(run.getRunId()).isEqualTo(stored.runId());
        });
    }

    /**
     * Checks that a row submitted a second time while an update for its first run was in flight reports the second run: pending, not ready,
     * under the second run's consents, with the second run still open.
     *
     * @param firstRun the id of the run the update named
     * @param stored the row as read after both writers finished
     * @param runs the runs of the row, oldest first
     */
    static void assertTheSecondRunStands(UUID firstRun, StoredQuery stored, List<QueryRun> runs) {
        assertThat(runs).hasSize(2);
        assertThat(runs.getFirst().getRunId()).isEqualTo(firstRun);
        assertThat(stored.runId()).isEqualTo(runs.getLast().getRunId());
        assertThat(stored.consents()).containsExactlyEntriesOf(runs.getLast().getConsents());
        assertThat(stored.consents()).isNotEqualTo(runs.getFirst().getConsents());
        assertThat(stored.status()).isEqualTo("PENDING");
        assertThat(stored.readyTime()).isNull();
        assertThat(runs.getFirst().getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(runs.getLast().getStatus()).isNull();
        assertThat(runs.getLast().getCompletedAt()).isNull();
    }

    /**
     * Checks one round of two racing saves onto a stored row: both were stored, no two runs share a submit time, and the row carries the
     * consents and the id of the run that reads back as the latest.
     *
     * @param round the number of rounds run so far, counting this one
     * @param outcomes how each save of this round ended
     * @param stored the row as read after the round
     * @param runs every run of the row, oldest first
     * @param latest the run the repository reports as the latest
     */
    static void assertRunsAreOrdered(int round, List<SaveOutcome> outcomes, StoredQuery stored, List<QueryRun> runs, QueryRun latest) {
        assertThat(outcomes).as("round %s", round).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(runs).as("round %s", round).hasSize(1 + 2 * round);
        assertThat(runs).as("round %s", round).extracting(QueryRun::getSubmittedAt).doesNotHaveDuplicates();
        assertThat(latest.getRunId()).as("round %s", round).isEqualTo(runs.getLast().getRunId());
        assertThat(stored.runId()).as("round %s", round).isEqualTo(latest.getRunId());
        assertThat(stored.consents()).as("round %s", round).containsExactlyEntriesOf(latest.getConsents());
    }
}
