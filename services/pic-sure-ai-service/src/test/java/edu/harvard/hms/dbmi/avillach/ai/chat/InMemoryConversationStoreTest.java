package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryConversationStoreTest {

    private static final ConversationStore.Key KEY = new ConversationStore.Key("owner-a", "conv-1");

    private final AtomicLong nanos = new AtomicLong();

    private InMemoryConversationStore store(int maxTurns) {
        return new InMemoryConversationStore(Duration.ofMinutes(60), maxTurns, 1_000_000, 2, 1000, nanos::get);
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
        InMemoryConversationStore store = new InMemoryConversationStore(Duration.ofMinutes(60), 50, 40, 10, 1000, nanos::get);
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

    private static ConversationStore.Key key(String owner, String conversation) {
        return new ConversationStore.Key(owner, conversation);
    }

    @Test
    void aThirdConversationDropsTheOwnersLeastRecentlyUsedOne() {
        InMemoryConversationStore store = store(50);
        store.append(key("owner-a", "c1"), turn("one"));
        store.append(key("owner-a", "c2"), turn("two"));
        store.turns(key("owner-a", "c1"));

        store.append(key("owner-a", "c3"), turn("three"));

        assertFalse(store.turns(key("owner-a", "c1")).isEmpty(), "c1 was used more recently than c2");
        assertTrue(store.turns(key("owner-a", "c2")).isEmpty());
        assertFalse(store.turns(key("owner-a", "c3")).isEmpty());
        assertEquals(Optional.of(ConversationStore.Eviction.LIMIT), store.evictionOf(key("owner-a", "c2")));
    }

    @Test
    void anotherOwnersConversationsAreNotCountedAgainstTheLimit() {
        InMemoryConversationStore store = store(50);
        store.append(key("owner-a", "c1"), turn("one"));
        store.append(key("owner-a", "c2"), turn("two"));

        store.append(key("owner-b", "c1"), turn("three"));

        assertFalse(store.turns(key("owner-a", "c1")).isEmpty());
        assertFalse(store.turns(key("owner-a", "c2")).isEmpty());
    }

    @Test
    void aNewOwnerPastTheOwnerLimitDisplacesTheOwnerIdleLongest() {
        InMemoryConversationStore store = new InMemoryConversationStore(Duration.ofMinutes(60), 50, 1_000_000, 2, 2, nanos::get);
        store.append(key("owner-a", "c1"), turn("one"));
        store.append(key("owner-a", "c2"), turn("two"));
        nanos.addAndGet(1);
        store.append(key("owner-b", "c1"), turn("three"));
        nanos.addAndGet(1);
        store.turns(key("owner-a", "c1"));
        store.turns(key("owner-a", "c2"));

        store.append(key("owner-c", "c1"), turn("four"));

        assertTrue(store.turns(key("owner-b", "c1")).isEmpty(), "owner-b was used least recently");
        assertFalse(store.turns(key("owner-a", "c1")).isEmpty());
        assertFalse(store.turns(key("owner-c", "c1")).isEmpty());
        assertEquals(Optional.of(ConversationStore.Eviction.CLEANUP), store.evictionOf(key("owner-b", "c1")));
        assertTrue(store.ownerCount() <= 2);
    }

    @Test
    void anOwnerWithOneRecentConversationIsNotDisplacedForHavingAnOldOne() {
        InMemoryConversationStore store = new InMemoryConversationStore(Duration.ofMinutes(60), 50, 1_000_000, 2, 2, nanos::get);
        store.append(key("owner-a", "old"), turn("one"));
        nanos.addAndGet(1);
        store.append(key("owner-b", "c1"), turn("two"));
        nanos.addAndGet(1);
        store.append(key("owner-a", "recent"), turn("three"));

        store.append(key("owner-c", "c1"), turn("four"));

        assertTrue(store.turns(key("owner-b", "c1")).isEmpty(), "owner-b's only conversation is the one used least recently by any owner");
        assertFalse(store.turns(key("owner-a", "old")).isEmpty());
        assertFalse(store.turns(key("owner-a", "recent")).isEmpty());
        assertEquals(Optional.empty(), store.evictionOf(key("owner-a", "recent")));
    }

    @Test
    void theNumberOfOwnersIsBounded() {
        InMemoryConversationStore store = new InMemoryConversationStore(Duration.ofMinutes(60), 50, 1_000_000, 2, 10, nanos::get);

        for (int i = 0; i < 1_000; i++) {
            store.append(key("token:" + i, "c1"), turn("one"));
        }

        assertEquals(10, store.ownerCount());
    }

    @Test
    void aDroppedConversationIsReportedUntilItIsStartedAgain() {
        InMemoryConversationStore store = store(50);
        store.append(key("owner-a", "c1"), turn("one"));
        store.append(key("owner-a", "c2"), turn("two"));
        store.append(key("owner-a", "c3"), turn("three"));
        assertTrue(store.evictionOf(key("owner-a", "c1")).isPresent());

        store.append(key("owner-a", "c1"), turn("again"));

        assertTrue(store.evictionOf(key("owner-a", "c1")).isEmpty());
    }

    @Test
    void anIdleConversationIsNotReportedAsDropped() {
        InMemoryConversationStore store = store(50);
        store.append(KEY, turn("one"));

        nanos.addAndGet(Duration.ofMinutes(61).toNanos());

        assertTrue(store.turns(KEY).isEmpty());
        assertTrue(store.evictionOf(KEY).isEmpty());
    }

    @Test
    void aConversationOverTheBudgetOnItsOwnIsDroppedAloneAndOthersAreKept() {
        // turn("one") weighs 15 characters and turn("") 9: one "one" turn fits a budget of 25 beside the small one, two do not.
        InMemoryConversationStore store = new InMemoryConversationStore(Duration.ofMinutes(60), 50, 25, 10, 1000, nanos::get);
        ConversationStore.Key other = key("owner-b", "c1");
        store.append(other, turn(""));
        store.append(KEY, turn("one"));
        store.turns(other);

        store.append(KEY, turn("two"));

        assertTrue(store.turns(KEY).isEmpty());
        assertEquals(ConversationStore.Eviction.CLEANUP, store.evictionOf(KEY).orElseThrow());
        assertFalse(store.turns(other).isEmpty());
    }

    @Test
    void anOverBudgetWriteEvictsOtherConversationsFirstAndKeepsItself() {
        InMemoryConversationStore store = new InMemoryConversationStore(Duration.ofMinutes(60), 50, 20, 10, 1000, nanos::get);
        ConversationStore.Key other = key("owner-b", "c1");
        store.append(other, turn("one"));

        store.append(KEY, turn("two"));

        assertEquals(1, store.turns(KEY).size());
        assertTrue(store.turns(other).isEmpty());
        assertEquals(ConversationStore.Eviction.CLEANUP, store.evictionOf(other).orElseThrow());
    }
}
