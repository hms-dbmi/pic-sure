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

    /**
     * @param response the turn's response, or null while it is still running
     * @param fingerprint {@link RequestFingerprint#of} of the request that claimed the turn
     */
    record TurnResult(ChatResponse response, String fingerprint) {

        boolean running() {
            return response == null;
        }
    }

    /** What {@link #claim} found. */
    sealed interface Claim {

        /** Nothing was stored: the caller runs the turn, and must then {@link #complete} or {@link #release} it. */
        record Granted() implements Claim {
        }

        /** The {@code requestId} already has a turn, running or finished. */
        record Existing(TurnResult result) implements Claim {
        }

        /** Another {@code requestId} from the same caller is still answering this same message. */
        record Duplicate(String requestId) implements Claim {
        }
    }

    /**
     * Marks the turn as running unless its {@code requestId} already has one, or the same caller is already answering the same message
     * under another {@code requestId}.
     *
     * @param fingerprint {@link RequestFingerprint#of} of the request
     */
    Claim claim(Key key, String fingerprint);

    void complete(Key key, String fingerprint, ChatResponse response);

    /** Forgets a claimed turn that failed, so a retry runs it again. */
    void release(Key key, String fingerprint);

    /** @return what is stored for the turn; empty when it never ran here, failed, or has expired */
    Optional<TurnResult> find(Key key);
}
