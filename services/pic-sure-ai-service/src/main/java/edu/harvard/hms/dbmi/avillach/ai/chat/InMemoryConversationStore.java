package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Keeps conversations in this instance's memory: lost on restart, and not shared between instances. A conversation is dropped once idle for
 * {@code idle-timeout}, which also bounds how stale a replayed lookup can be if the caller's access changes mid-conversation. Past
 * {@code max-turns} the oldest turns are dropped, and with them what their lookups showed, so a path only they returned must be searched
 * again: refused, never wrongly allowed.
 *
 * <p>Three bounds drop whole conversations, least recently used first, and remember having done so ({@link #evictionOf}) so the caller can
 * be told: a caller starting a conversation past {@code max-conversations-per-owner} loses their own least recently used one; a new caller
 * past {@code max-owners} displaces the caller whose conversations were used least recently, all of them; and past {@code max-total-chars}
 * across all conversations the least recently used go first. A conversation that is over {@code max-total-chars} on its own is dropped
 * alone, and a conversation never displaces itself. One lock guards everything, which is cheap next to a model call.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.conversation", name = "store", havingValue = "memory", matchIfMissing = true)
class InMemoryConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryConversationStore.class);

    private static final class Entry {
        List<ConversationTurn> turns;
        long weight;
        long lastUsed;
    }

    /** In access order, so the first entry is the least recently used conversation. */
    private final LinkedHashMap<Key, Entry> conversations = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Integer> conversationsByOwner = new HashMap<>();
    /** Owners in access order, so the first is the one whose latest conversation was used longest ago. */
    private final LinkedHashMap<String, Boolean> ownersByRecency = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<Key, Eviction> evicted;
    private final List<Consumer<String>> ownerDropped = new CopyOnWriteArrayList<>();
    /** Owners who lost their last conversation during the current call, told to the listeners once it is done. Guarded by {@code this}. */
    private final Set<String> droppedOwners = new LinkedHashSet<>();
    private final long idleNanos;
    private final int maxTurns;
    private final long maxTotalChars;
    private final int maxConversationsPerOwner;
    private final int maxOwners;
    private final Ticker ticker;
    private long totalChars;

    @Autowired
    InMemoryConversationStore(
        @Value("${picsure.ai.conversation.idle-timeout}") Duration idleTimeout, @Value("${picsure.ai.conversation.max-turns}") int maxTurns,
        @Value("${picsure.ai.conversation.max-total-chars}") long maxTotalChars,
        @Value("${picsure.ai.conversation.max-conversations-per-owner}") int maxConversationsPerOwner,
        @Value("${picsure.ai.conversation.max-owners}") int maxOwners
    ) {
        this(idleTimeout, maxTurns, maxTotalChars, maxConversationsPerOwner, maxOwners, Ticker.systemTicker());
    }

    InMemoryConversationStore(
        Duration idleTimeout, int maxTurns, long maxTotalChars, int maxConversationsPerOwner, int maxOwners, Ticker ticker
    ) {
        if (maxConversationsPerOwner < 1 || maxOwners < 1) {
            throw new IllegalArgumentException("max-conversations-per-owner and max-owners must each be at least 1");
        }
        this.idleNanos = idleTimeout.toNanos();
        this.maxTurns = maxTurns;
        this.maxTotalChars = maxTotalChars;
        this.maxConversationsPerOwner = maxConversationsPerOwner;
        this.maxOwners = maxOwners;
        this.ticker = ticker;
        int remembered = (int) Math.min((long) maxOwners * maxConversationsPerOwner, 100_000L);
        this.evicted = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Eviction> eldest) {
                return size() > remembered;
            }
        };
    }

    @Override
    public synchronized List<ConversationTurn> turns(Key key) {
        dropIdle();
        tellOwnersDropped();
        Entry entry = conversations.get(key);
        if (entry == null) {
            return List.of();
        }
        entry.lastUsed = ticker.read();
        ownersByRecency.put(key.owner(), Boolean.TRUE);
        return entry.turns;
    }

    @Override
    public synchronized void append(Key key, ConversationTurn turn) {
        appendTurn(key, turn);
        tellOwnersDropped();
    }

    private void appendTurn(Key key, ConversationTurn turn) {
        if (maxTurns <= 0) {
            return;
        }
        dropIdle();
        Entry entry = conversations.get(key);
        if (entry == null) {
            makeRoomFor(key.owner());
            entry = new Entry();
            entry.turns = List.of();
            conversations.put(key, entry);
            conversationsByOwner.merge(key.owner(), 1, Integer::sum);
            evicted.remove(key);
        }
        List<ConversationTurn> all = new ArrayList<>(entry.turns);
        all.add(turn);
        entry.turns = List.copyOf(all.subList(Math.max(0, all.size() - maxTurns), all.size()));
        totalChars -= entry.weight;
        entry.weight = entry.turns.stream().mapToLong(ConversationTurn::size).sum();
        totalChars += entry.weight;
        entry.lastUsed = ticker.read();
        ownersByRecency.put(key.owner(), Boolean.TRUE);
        if (entry.weight > maxTotalChars) {
            // It cannot fit even alone, and clearing everyone else's would not help: drop only this one.
            evict(key, Eviction.CLEANUP);
            return;
        }
        // Never this conversation, which is the most recently used, so only others go: it fits the budget on its own.
        while (totalChars > maxTotalChars && conversations.size() > 1) {
            evict(conversations.keySet().iterator().next(), Eviction.CLEANUP);
        }
    }

    @Override
    public void onOwnerDropped(Consumer<String> listener) {
        ownerDropped.add(listener);
    }

    @Override
    public synchronized Optional<Eviction> evictionOf(Key key) {
        return Optional.ofNullable(evicted.get(key));
    }

    /** @return how many callers hold a conversation, after dropping the idle */
    synchronized int ownerCount() {
        dropIdle();
        return conversationsByOwner.size();
    }

    /** Frees the slot a new conversation of this owner needs, by their own limit first and then the store's owner limit. */
    private void makeRoomFor(String owner) {
        int held = conversationsByOwner.getOrDefault(owner, 0);
        if (held >= maxConversationsPerOwner) {
            for (Key key : conversations.keySet()) {
                if (key.owner().equals(owner)) {
                    evict(key, Eviction.LIMIT);
                    // Replaced by the conversation about to start, so the owner is not dropped.
                    droppedOwners.remove(owner);
                    return;
                }
            }
        }
        if (held == 0 && conversationsByOwner.size() >= maxOwners) {
            String displaced = idlestOwner();
            for (Key key : List.copyOf(conversations.keySet())) {
                if (key.owner().equals(displaced)) {
                    evict(key, Eviction.CLEANUP);
                }
            }
        }
    }

    /** The owner whose latest use is the oldest. An owner's place is set by its most recent conversation, not its oldest. */
    private String idlestOwner() {
        return ownersByRecency.keySet().iterator().next();
    }

    private void evict(Key key, Eviction why) {
        forget(key);
        evicted.put(key, why);
    }

    private void forget(Key key) {
        Entry entry = conversations.remove(key);
        if (entry != null) {
            uncount(key, entry);
        }
    }

    private void uncount(Key key, Entry entry) {
        totalChars -= entry.weight;
        conversationsByOwner.computeIfPresent(key.owner(), (owner, count) -> count > 1 ? count - 1 : null);
        if (!conversationsByOwner.containsKey(key.owner())) {
            ownersByRecency.remove(key.owner());
            droppedOwners.add(key.owner());
        }
    }

    /**
     * Tells the listeners about owners who lost their last conversation during the call, once it is done. An owner whose limit-evicted
     * conversation is replaced by a new one is not reported. A failing listener is logged and does not stop the rest.
     */
    private void tellOwnersDropped() {
        if (droppedOwners.isEmpty()) {
            return;
        }
        List<String> owners = List.copyOf(droppedOwners);
        droppedOwners.clear();
        for (String owner : owners) {
            for (Consumer<String> listener : ownerDropped) {
                try {
                    listener.accept(owner);
                } catch (RuntimeException e) {
                    log.warn("Owner-dropped listener failed", e);
                }
            }
        }
    }

    /** Idle conversations are dropped without a record: they were left, not displaced. */
    private void dropIdle() {
        long now = ticker.read();
        Iterator<Map.Entry<Key, Entry>> oldest = conversations.entrySet().iterator();
        while (oldest.hasNext()) {
            Map.Entry<Key, Entry> next = oldest.next();
            if (now - next.getValue().lastUsed < idleNanos) {
                return;
            }
            oldest.remove();
            uncount(next.getKey(), next.getValue());
        }
    }
}
