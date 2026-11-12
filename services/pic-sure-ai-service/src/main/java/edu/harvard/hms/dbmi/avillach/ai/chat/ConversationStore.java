package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

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
}
