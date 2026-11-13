package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTurnResultStoreTest {

    private static final TurnResultStore.Key KEY = new TurnResultStore.Key("owner-a", "req-1");
    private static final String PRINT = "print-1";
    private static final ChatResponse RESPONSE = ChatResponse.textOnly("Hello");

    private final AtomicLong nanos = new AtomicLong();
    private final InMemoryTurnResultStore store =
        new InMemoryTurnResultStore(Duration.ofMinutes(60), Duration.ofMinutes(60), 100, 1000, nanos::get);

    private TurnResultStore.TurnResult existing(TurnResultStore.Claim claim) {
        return assertInstanceOf(TurnResultStore.Claim.Existing.class, claim).result();
    }

    @Test
    void onlyTheFirstClaimRunsTheTurn() {
        assertInstanceOf(TurnResultStore.Claim.Granted.class, store.claim(KEY, PRINT));

        assertTrue(existing(store.claim(KEY, PRINT)).running());
    }

    @Test
    void aCompletedTurnIsReturnedToLaterClaimsAndLookups() {
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);

        assertEquals(RESPONSE, existing(store.claim(KEY, PRINT)).response());
        assertEquals(RESPONSE, store.find(KEY).orElseThrow().response());
    }

    @Test
    void aStoredTurnKeepsTheFingerprintItWasClaimedWith() {
        store.claim(KEY, PRINT);

        assertEquals(PRINT, existing(store.claim(KEY, "another")).fingerprint());
    }

    @Test
    void aReleasedTurnCanBeClaimedAgain() {
        store.claim(KEY, PRINT);
        store.release(KEY, PRINT);

        assertTrue(store.find(KEY).isEmpty());
        assertInstanceOf(TurnResultStore.Claim.Granted.class, store.claim(KEY, PRINT));
    }

    @Test
    void releaseNeverDropsACompletedTurn() {
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);
        store.release(KEY, PRINT);

        assertEquals(RESPONSE, store.find(KEY).orElseThrow().response());
    }

    @Test
    void theSameMessageUnderAnotherRequestIdIsADuplicateWhileTheFirstRuns() {
        store.claim(KEY, PRINT);

        TurnResultStore.Claim second = store.claim(new TurnResultStore.Key("owner-a", "req-2"), PRINT);

        assertEquals("req-1", assertInstanceOf(TurnResultStore.Claim.Duplicate.class, second).requestId());
        assertTrue(store.find(new TurnResultStore.Key("owner-a", "req-2")).isEmpty(), "a refused duplicate leaves nothing behind");
    }

    @Test
    void theSameMessageUnderAnotherRequestIdRunsOnceTheFirstHasFinishedOrFailed() {
        TurnResultStore.Key other = new TurnResultStore.Key("owner-a", "req-2");
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);
        assertInstanceOf(TurnResultStore.Claim.Granted.class, store.claim(other, PRINT));

        store.release(other, PRINT);
        assertInstanceOf(TurnResultStore.Claim.Granted.class, store.claim(new TurnResultStore.Key("owner-a", "req-3"), PRINT));
    }

    @Test
    void anotherOwnerSendingTheSameMessageIsNotADuplicate() {
        store.claim(KEY, PRINT);

        assertInstanceOf(TurnResultStore.Claim.Granted.class, store.claim(new TurnResultStore.Key("owner-b", "req-1"), PRINT));
    }

    @Test
    void anotherOwnerCannotSeeTheTurn() {
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);

        assertTrue(store.find(new TurnResultStore.Key("owner-b", "req-1")).isEmpty());
    }

    @Test
    void aResultExpiresAfterItsTtl() {
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);

        nanos.addAndGet(Duration.ofMinutes(61).toNanos());

        assertTrue(store.find(KEY).isEmpty());
    }

    @Test
    void oneCallerFillingTheirQuotaNeverEvictsAnotherCallersResult() {
        InMemoryTurnResultStore small = new InMemoryTurnResultStore(Duration.ofMinutes(60), Duration.ofMinutes(60), 5, 1000, nanos::get);
        TurnResultStore.Key victim = new TurnResultStore.Key("owner-b", "req-1");
        small.claim(victim, PRINT);
        small.complete(victim, PRINT, RESPONSE);

        for (int i = 0; i < 50; i++) {
            TurnResultStore.Key key = new TurnResultStore.Key("owner-a", "flood-" + i);
            small.claim(key, "print-" + i);
            small.complete(key, "print-" + i, RESPONSE);
        }

        assertTrue(small.find(victim).isPresent());
        assertTrue(small.claim(victim, PRINT) instanceof TurnResultStore.Claim.Existing);
    }

    @Test
    void aRunningClaimIsNeverEvictedByTheSameOwnersCompletedTurns() {
        InMemoryTurnResultStore small = new InMemoryTurnResultStore(Duration.ofMinutes(60), Duration.ofMinutes(60), 5, 1000, nanos::get);
        small.claim(KEY, PRINT);

        for (int i = 0; i < 50; i++) {
            TurnResultStore.Key other = new TurnResultStore.Key(KEY.owner(), "flood-" + i);
            small.claim(other, "print-" + i);
            small.complete(other, "print-" + i, RESPONSE);
        }

        assertTrue(small.find(KEY).orElseThrow().running());
        assertTrue(small.claim(new TurnResultStore.Key(KEY.owner(), "req-retry"), PRINT) instanceof TurnResultStore.Claim.Duplicate);
    }

    @Test
    void aResultTtlShorterThanTheConversationIdleTimeoutIsRefused() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new InMemoryTurnResultStore(Duration.ofMinutes(30), Duration.ofMinutes(60), 100, 1000, nanos::get)
        );
    }

    @Test
    void theNumberOfOwnersIsBounded() {
        InMemoryTurnResultStore small = new InMemoryTurnResultStore(Duration.ofMinutes(60), Duration.ofMinutes(60), 5, 10, nanos::get);

        for (int i = 0; i < 1_000; i++) {
            TurnResultStore.Key key = new TurnResultStore.Key("token:" + i, "req-1");
            small.claim(key, PRINT);
            small.complete(key, PRINT, RESPONSE);
        }

        assertTrue(small.ownerCount() <= 10);
    }

    @Test
    void aNewCallerPastTheLimitDisplacesTheCallerIdleLongestAndKeepsItsOwnResult() {
        InMemoryTurnResultStore small = new InMemoryTurnResultStore(Duration.ofMinutes(60), Duration.ofMinutes(60), 5, 2, nanos::get);
        TurnResultStore.Key first = new TurnResultStore.Key("owner-a", "req-1");
        TurnResultStore.Key second = new TurnResultStore.Key("owner-b", "req-1");
        TurnResultStore.Key third = new TurnResultStore.Key("owner-c", "req-1");
        for (TurnResultStore.Key key : List.of(first, second)) {
            small.claim(key, PRINT);
            small.complete(key, PRINT, RESPONSE);
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        }
        // The first caller is used again, so the second is now the one idle longest.
        assertTrue(small.find(first).isPresent());
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());

        small.claim(third, PRINT);
        small.complete(third, PRINT, RESPONSE);

        assertTrue(small.find(third).isPresent());
        assertTrue(small.find(first).isPresent());
        assertTrue(small.find(second).isEmpty());
        assertEquals(2, small.ownerCount());
    }

    @Test
    void aClaimThatStoresNothingHoldsNoOwner() {
        for (int i = 0; i < 100; i++) {
            TurnResultStore.Key key = new TurnResultStore.Key("token:" + i, "req-1");
            store.claim(key, PRINT);
            store.release(key, PRINT);
        }

        assertEquals(0, store.ownerCount());
    }

    private InMemoryConversationStore linkedConversations(int maxOwners) {
        return linkedConversations(Duration.ofMinutes(60), 2, maxOwners);
    }

    private InMemoryConversationStore linkedConversations(Duration idleTimeout, int perOwner, int maxOwners) {
        InMemoryConversationStore conversations =
            new InMemoryConversationStore(idleTimeout, 50, 1_000_000, perOwner, maxOwners, nanos::get);
        conversations.onOwnerDropped(store::forgetResultsOf);
        return conversations;
    }

    private static ConversationTurn turn() {
        return new ConversationTurn("hello", "reply", List.of(), null);
    }

    @Test
    void anOwnerDisplacedFromTheConversationStoreLosesTheirResults() {
        InMemoryConversationStore conversations = linkedConversations(1);
        conversations.append(new ConversationStore.Key("owner-a", "conv-1"), turn());
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);

        conversations.append(new ConversationStore.Key("owner-b", "conv-1"), turn());

        assertTrue(store.find(KEY).isEmpty());
    }

    @Test
    void anOwnerWhoStillHoldsAConversationKeepsTheirResults() {
        InMemoryConversationStore conversations = linkedConversations(1000);
        conversations.append(new ConversationStore.Key("owner-a", "conv-1"), turn());
        conversations.append(new ConversationStore.Key("owner-a", "conv-2"), turn());
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);

        conversations.append(new ConversationStore.Key("owner-a", "conv-3"), turn());

        assertEquals(RESPONSE, store.find(KEY).orElseThrow().response());
    }

    @Test
    void anOwnerWhoseConversationWentIdleLosesTheirResults() {
        // Idle well before the 60 minute result TTL, so only the link can have dropped the result.
        InMemoryConversationStore conversations = linkedConversations(Duration.ofMinutes(10), 2, 1000);
        conversations.append(new ConversationStore.Key("owner-a", "conv-1"), turn());
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);

        nanos.addAndGet(Duration.ofMinutes(11).toNanos());
        conversations.turns(new ConversationStore.Key("owner-b", "conv-1"));

        assertTrue(store.find(KEY).isEmpty());
    }

    @Test
    void anOwnerWhoseOldConversationIsReplacedKeepsTheirResults() {
        InMemoryConversationStore conversations = linkedConversations(Duration.ofMinutes(60), 1, 1000);
        conversations.append(new ConversationStore.Key("owner-a", "conv-1"), turn());
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);

        conversations.append(new ConversationStore.Key("owner-a", "conv-2"), turn());

        assertEquals(RESPONSE, store.find(KEY).orElseThrow().response());
    }

    @Test
    void aFailingListenerDoesNotStopTheOthersOrTheStore() {
        // The failing listener goes first, so the link registered after it only works if the failure does not stop the rest.
        InMemoryConversationStore conversations = new InMemoryConversationStore(Duration.ofMinutes(60), 50, 1_000_000, 2, 1, nanos::get);
        conversations.onOwnerDropped(owner -> {
            throw new IllegalStateException("boom");
        });
        conversations.onOwnerDropped(store::forgetResultsOf);
        conversations.append(new ConversationStore.Key("owner-a", "conv-1"), turn());
        store.claim(KEY, PRINT);
        store.complete(KEY, PRINT, RESPONSE);

        conversations.append(new ConversationStore.Key("owner-b", "conv-1"), turn());

        assertEquals(1, conversations.ownerCount());
        assertTrue(store.find(KEY).isEmpty());
    }
}
