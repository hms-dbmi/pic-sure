package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.time.Duration;
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
        new InMemoryTurnResultStore(Duration.ofMinutes(60), Duration.ofMinutes(60), 100, nanos::get);

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
        InMemoryTurnResultStore small = new InMemoryTurnResultStore(Duration.ofMinutes(60), Duration.ofMinutes(60), 5, nanos::get);
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
    void aRunningClaimIsNeverEvictedByOtherCompletedTurns() {
        InMemoryTurnResultStore small = new InMemoryTurnResultStore(Duration.ofMinutes(60), Duration.ofMinutes(60), 5, nanos::get);
        small.claim(KEY, PRINT);

        for (int i = 0; i < 50; i++) {
            TurnResultStore.Key other = new TurnResultStore.Key("owner-b", "req-" + i);
            small.claim(other, "print-" + i);
            small.complete(other, "print-" + i, RESPONSE);
        }

        assertTrue(small.find(KEY).isPresent());
        assertTrue(small.claim(new TurnResultStore.Key(KEY.owner(), "req-retry"), PRINT) instanceof TurnResultStore.Claim.Duplicate);
    }

    @Test
    void aResultTtlShorterThanTheConversationIdleTimeoutIsRefused() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new InMemoryTurnResultStore(Duration.ofMinutes(30), Duration.ofMinutes(60), 100, nanos::get)
        );
    }
}
