package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Keeps conversations in this instance's memory: lost on restart, and not shared between instances. A conversation is dropped once idle for
 * {@code idle-timeout}, which also bounds how stale a replayed lookup can be if the caller's access changes mid-conversation. Past
 * {@code max-turns} the oldest turns are dropped, and with them what their lookups showed, so a path only they returned must be searched
 * again: refused, never wrongly allowed. Past {@code max-total-chars} across all conversations, the least recently used go first.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.conversation", name = "store", havingValue = "memory", matchIfMissing = true)
class InMemoryConversationStore implements ConversationStore {

    private final Cache<Key, List<ConversationTurn>> conversations;
    private final int maxTurns;

    @Autowired
    InMemoryConversationStore(
        @Value("${picsure.ai.conversation.idle-timeout}") Duration idleTimeout, @Value("${picsure.ai.conversation.max-turns}") int maxTurns,
        @Value("${picsure.ai.conversation.max-total-chars}") long maxTotalChars
    ) {
        this(idleTimeout, maxTurns, maxTotalChars, Ticker.systemTicker());
    }

    InMemoryConversationStore(Duration idleTimeout, int maxTurns, long maxTotalChars, Ticker ticker) {
        this.maxTurns = maxTurns;
        this.conversations = Caffeine.newBuilder().expireAfterAccess(idleTimeout).maximumWeight(maxTotalChars)
            .weigher((Key key, List<ConversationTurn> turns) -> weight(turns)).ticker(ticker).build();
    }

    @Override
    public List<ConversationTurn> turns(Key key) {
        List<ConversationTurn> turns = conversations.getIfPresent(key);
        return turns == null ? List.of() : turns;
    }

    @Override
    public void append(Key key, ConversationTurn turn) {
        if (maxTurns <= 0) {
            return;
        }
        conversations.asMap().merge(key, List.of(turn), (earlier, added) -> {
            List<ConversationTurn> all = new ArrayList<>(earlier);
            all.addAll(added);
            return List.copyOf(all.subList(Math.max(0, all.size() - maxTurns), all.size()));
        });
    }

    private static int weight(List<ConversationTurn> turns) {
        long chars = turns.stream().mapToLong(ConversationTurn::size).sum();
        return (int) Math.min(chars, Integer.MAX_VALUE);
    }
}
