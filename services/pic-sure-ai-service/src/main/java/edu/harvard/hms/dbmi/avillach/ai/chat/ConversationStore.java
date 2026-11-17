package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Where a conversation's earlier turns are kept between requests, so the next turn can show them to the model and rebuild what its guards
 * have seen. Behind an interface so {@link InMemoryConversationStore} can be replaced by a shared store once the service runs more than one
 * instance or must keep conversations across a restart.
 */
public interface ConversationStore {

    /**
     * @param owner {@link CallerContext#owner()}, so a caller can only ever load their own conversations
     * @param conversationId the request's conversation id
     */
    record Key(String owner, String conversationId) {
    }

    /** @return the conversation's turns, oldest first; empty when it is unknown or has expired */
    List<ConversationTurn> turns(Key key);

    /** Adds a finished turn to the end of the conversation, starting the conversation if it is new. */
    void append(Key key, ConversationTurn turn);

    /** Why a store dropped a conversation its caller had not left idle. */
    enum Eviction {
        /** The caller started more conversations than are kept at once, and this was their least recently used. */
        LIMIT,
        /** The store was full, and this caller's conversations were the least recently used of all. */
        CLEANUP
    }

    /**
     * @return why the store dropped this conversation, until a later turn starts it again; empty when it was never dropped for room, or the
     *         store does not say. A conversation that merely went idle is not reported.
     */
    default Optional<Eviction> evictionOf(Key key) {
        return Optional.empty();
    }

    /**
     * Registers a listener told the owner whenever the store no longer holds any conversation of theirs, for whatever reason, so anything
     * kept per owner elsewhere, such as their stored turn results, can go with it. A store that does not report this ignores it.
     */
    default void onOwnerDropped(Consumer<String> listener) {}
}
