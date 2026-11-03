package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.Optional;

/**
 * Each turn's response, kept by {@code requestId} so a client that stopped waiting or lost its connection can retry without running the
 * turn twice ({@code PLAN.md}'s "Timeouts &amp; partial results"). Behind an interface for the same reason as {@link ConversationStore}; a
 * shared implementation needs {@link #claim} to be atomic across instances.
 */
public interface TurnResultStore {

    /**
     * @param owner {@link CallerContext#owner()}, so a caller can only ever read their own replies
     * @param requestId the request's idempotency key
     */
    record Key(String owner, String requestId) {
    }

    /** @param response the turn's response, or null while it is still running */
    record TurnResult(ChatResponse response) {

        static final TurnResult RUNNING = new TurnResult(null);

        boolean running() {
            return response == null;
        }
    }

    /**
     * Marks the turn as running unless it already is or has finished.
     *
     * @return empty when this call claimed it, and the caller must then {@link #complete} or {@link #release} it; otherwise what is stored
     */
    Optional<TurnResult> claim(Key key);

    void complete(Key key, ChatResponse response);

    /** Forgets a claimed turn that failed, so a retry runs it again. */
    void release(Key key);

    /** @return what is stored for the turn; empty when it never ran here, failed, or has expired */
    Optional<TurnResult> find(Key key);
}
