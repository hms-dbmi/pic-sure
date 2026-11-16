package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryConversationStoreTest {

    private static final ConversationStore.Key KEY = new ConversationStore.Key("owner-a", "conv-1");

    private final AtomicLong nanos = new AtomicLong();

    private InMemoryConversationStore store(int maxTurns) {
        return new InMemoryConversationStore(Duration.ofMinutes(60), maxTurns, 1_000_000, nanos::get);
    }

    private static ConversationTurn turn(String message) {
        return new ConversationTurn(message, "reply to " + message, List.of(), null);
    }

    @Test
    void turnsComeBackOldestFirst() {
        InMemoryConversationStore store = store(50);

        store.append(KEY, turn("one"));
        store.append(KEY, turn("two"));

        assertEquals(List.of("one", "two"), store.turns(KEY).stream().map(ConversationTurn::message).toList());
    }

    @Test
    void aCapOfZeroKeepsNothing() {
        InMemoryConversationStore store = store(0);

        store.append(KEY, turn("one"));

        assertTrue(store.turns(KEY).isEmpty());
    }

    @Test
    void theOldestTurnsAreDroppedPastTheCap() {
        InMemoryConversationStore store = store(2);

        store.append(KEY, turn("one"));
        store.append(KEY, turn("two"));
        store.append(KEY, turn("three"));

        assertEquals(List.of("two", "three"), store.turns(KEY).stream().map(ConversationTurn::message).toList());
    }

    @Test
    void anotherOwnerWithTheSameConversationIdGetsNothing() {
        InMemoryConversationStore store = store(50);

        store.append(KEY, turn("one"));

        assertTrue(store.turns(new ConversationStore.Key("owner-b", "conv-1")).isEmpty());
    }

    @Test
    void anIdleConversationExpiresButOneInUseDoesNot() {
        InMemoryConversationStore store = store(50);
        store.append(KEY, turn("one"));

        nanos.addAndGet(Duration.ofMinutes(45).toNanos());
        assertEquals(1, store.turns(KEY).size(), "reading it counts as use");
        nanos.addAndGet(Duration.ofMinutes(45).toNanos());
        assertEquals(1, store.turns(KEY).size());
        nanos.addAndGet(Duration.ofMinutes(61).toNanos());

        assertTrue(store.turns(KEY).isEmpty());
    }

    @Test
    void conversationsPastTheTotalSizeAreEvictedWhileTheStoreKeepsWhatFits() throws Exception {
        // "reply to xx" turns weigh 15 characters each; a budget of 40 holds two conversations, not three.
        InMemoryConversationStore store = new InMemoryConversationStore(Duration.ofMinutes(60), 50, 40, nanos::get);
        List<ConversationStore.Key> keys = List.of(
            new ConversationStore.Key("owner-a", "c1"), new ConversationStore.Key("owner-a", "c2"),
            new ConversationStore.Key("owner-a", "c3")
        );

        keys.forEach(key -> store.append(key, turn("one")));

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (kept(store, keys) > 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(kept(store, keys) <= 2, "the budget cannot hold all three");
        assertTrue(kept(store, keys) >= 1, "eviction stops once the rest fits");
    }

    private static long kept(InMemoryConversationStore store, List<ConversationStore.Key> keys) {
        return keys.stream().filter(key -> !store.turns(key).isEmpty()).count();
    }
}
