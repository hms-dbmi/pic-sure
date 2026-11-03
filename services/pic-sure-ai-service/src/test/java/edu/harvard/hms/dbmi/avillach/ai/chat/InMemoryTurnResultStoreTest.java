package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTurnResultStoreTest {

    private static final TurnResultStore.Key KEY = new TurnResultStore.Key("owner-a", "req-1");
    private static final ChatResponse RESPONSE = ChatResponse.textOnly("Hello");

    private final AtomicLong nanos = new AtomicLong();
    private final InMemoryTurnResultStore store = new InMemoryTurnResultStore(Duration.ofMinutes(60), 100, nanos::get);

    @Test
    void onlyTheFirstClaimRunsTheTurn() {
        assertTrue(store.claim(KEY).isEmpty());

        assertTrue(store.claim(KEY).orElseThrow().running());
    }

    @Test
    void aCompletedTurnIsReturnedToLaterClaimsAndLookups() {
        store.claim(KEY);
        store.complete(KEY, RESPONSE);

        assertEquals(RESPONSE, store.claim(KEY).orElseThrow().response());
        assertEquals(RESPONSE, store.find(KEY).orElseThrow().response());
    }

    @Test
    void aReleasedTurnCanBeClaimedAgain() {
        store.claim(KEY);
        store.release(KEY);

        assertTrue(store.find(KEY).isEmpty());
        assertTrue(store.claim(KEY).isEmpty());
    }

    @Test
    void releaseNeverDropsACompletedTurn() {
        store.claim(KEY);
        store.complete(KEY, RESPONSE);
        store.release(KEY);

        assertEquals(RESPONSE, store.find(KEY).orElseThrow().response());
    }

    @Test
    void anotherOwnerCannotSeeTheTurn() {
        store.claim(KEY);
        store.complete(KEY, RESPONSE);

        assertTrue(store.find(new TurnResultStore.Key("owner-b", "req-1")).isEmpty());
    }

    @Test
    void aResultExpiresAfterItsTtl() {
        store.claim(KEY);
        store.complete(KEY, RESPONSE);

        nanos.addAndGet(Duration.ofMinutes(61).toNanos());

        assertTrue(store.find(KEY).isEmpty());
    }
}
