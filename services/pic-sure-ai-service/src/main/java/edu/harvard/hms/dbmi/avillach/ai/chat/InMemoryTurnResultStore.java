package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Keeps turn results in this instance's memory, each for {@code result-ttl} after it was written. A turn still marked running when its
 * instance dies simply expires. The result TTL must be at least the conversation idle timeout, so a retry cannot run a turn again while its
 * conversation still holds the first run. Once the conversation store holds none of a caller's conversations, their results go too, so a
 * retry after that runs the turn afresh. <p> Completed results are bounded per caller, so one caller cycling through {@code requestId}s can
 * only push out their own replies, and across callers by {@code max-owners}, so a flood of new callers cannot grow memory without limit:
 * the callers idle longest are dropped first, with their replies. <p> A running claim is kept apart from the completed results, so neither
 * another caller nor the same caller's other turns can evict it. It is removed when its turn completes or fails, and an orphan expires with
 * the TTL.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.conversation", name = "store", havingValue = "memory", matchIfMissing = true)
class InMemoryTurnResultStore implements TurnResultStore {

    private static final class Bucket {
        final Cache<String, TurnResult> results;
        long lastUsed;

        Bucket(Cache<String, TurnResult> results) {
            this.results = results;
        }
    }

    /**
     * Each caller's completed turn results by {@code requestId}, in access order so the first entry is the caller idle longest. A plain map
     * rather than a size-bounded Caffeine cache, which evicts by frequency and could drop the bucket just created for a finished turn. A
     * caller's bucket is dropped once idle for the TTL, and the one used least recently goes first past {@code max-owners}. Guarded by
     * {@code this}.
     */
    private final LinkedHashMap<String, Bucket> results = new LinkedHashMap<>(16, 0.75f, true);
    private final Duration ttl;
    private final long maxOwners;
    private final long maxResultsPerOwner;
    private final Ticker ticker;
    /** The {@code requestId} answering each message right now, by {@code Key(owner, fingerprint)}. */
    private final Cache<Key, String> running;
    /** The turns being answered right now, by key. Holds no completed result and has no size bound, so it cannot evict a running claim. */
    private final Cache<Key, TurnResult> inFlight;

    @Autowired
    InMemoryTurnResultStore(
        @Value("${picsure.ai.conversation.result-ttl}") Duration ttl,
        @Value("${picsure.ai.conversation.idle-timeout}") Duration idleTimeout,
        @Value("${picsure.ai.conversation.max-results-per-owner}") long maxResultsPerOwner,
        @Value("${picsure.ai.conversation.max-owners}") long maxOwners
    ) {
        this(ttl, idleTimeout, maxResultsPerOwner, maxOwners, Ticker.systemTicker());
    }

    /** Optional, so the store also works without a conversation store to follow. */
    @Autowired(required = false)
    void followConversations(ConversationStore conversations) {
        conversations.onOwnerDropped(this::forgetResultsOf);
    }

    InMemoryTurnResultStore(Duration ttl, Duration idleTimeout, long maxResultsPerOwner, long maxOwners, Ticker ticker) {
        if (ttl.compareTo(idleTimeout) < 0) {
            throw new IllegalArgumentException(
                "picsure.ai.conversation.result-ttl (" + ttl + ") must be at least idle-timeout (" + idleTimeout
                    + "), or a late retry runs a turn its conversation already holds"
            );
        }
        if (maxOwners < 1) {
            throw new IllegalArgumentException("picsure.ai.conversation.max-owners must be at least 1");
        }
        this.ttl = ttl;
        this.maxOwners = maxOwners;
        this.maxResultsPerOwner = maxResultsPerOwner;
        this.ticker = ticker;
        this.running = Caffeine.newBuilder().expireAfterWrite(ttl).ticker(ticker).build();
        this.inFlight = Caffeine.newBuilder().expireAfterWrite(ttl).ticker(ticker).build();
    }

    /** Stores under the lock that decides who is evicted, so the caller just stored for is the most recently used and cannot be dropped. */
    private synchronized void store(Key key, TurnResult result) {
        dropIdle();
        Bucket bucket = results.get(key.owner());
        if (bucket == null) {
            bucket = new Bucket(
                Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxResultsPerOwner).executor(Runnable::run).ticker(ticker).build()
            );
            results.put(key.owner(), bucket);
            while (results.size() > maxOwners) {
                results.remove(results.keySet().iterator().next());
            }
        }
        bucket.lastUsed = ticker.read();
        bucket.results.put(key.requestId(), result);
    }

    private synchronized TurnResult completed(Key key) {
        dropIdle();
        Bucket bucket = results.get(key.owner());
        if (bucket == null) {
            return null;
        }
        bucket.lastUsed = ticker.read();
        return bucket.results.getIfPresent(key.requestId());
    }

    /** Callers idle for the TTL hold only expired results, so they are dropped. */
    private void dropIdle() {
        long now = ticker.read();
        long idleNanos = ttl.toNanos();
        Iterator<Bucket> oldest = results.values().iterator();
        while (oldest.hasNext() && now - oldest.next().lastUsed >= idleNanos) {
            oldest.remove();
        }
    }

    /** Without any conversation of theirs to retry into, a caller's completed results are orphans, so they are dropped with it. */
    synchronized void forgetResultsOf(String owner) {
        results.remove(owner);
    }

    /** @return how many callers' results are held, after dropping the expired */
    synchronized long ownerCount() {
        dropIdle();
        return results.size();
    }

    @Override
    public Claim claim(Key key, String fingerprint) {
        TurnResult done = completed(key);
        if (done != null) {
            return new Claim.Existing(done);
        }
        TurnResult mine = new TurnResult(null, fingerprint);
        TurnResult earlier = inFlight.asMap().putIfAbsent(key, mine);
        if (earlier != null) {
            return new Claim.Existing(earlier);
        }
        // The turn may have completed between the check above and winning the claim, which would otherwise run it a second time.
        done = completed(key);
        if (done != null) {
            inFlight.asMap().remove(key, mine);
            return new Claim.Existing(done);
        }
        String other = running.asMap().putIfAbsent(new Key(key.owner(), fingerprint), key.requestId());
        if (other != null) {
            inFlight.asMap().remove(key, mine);
            return new Claim.Duplicate(other);
        }
        return new Claim.Granted();
    }

    @Override
    public void complete(Key key, String fingerprint, ChatResponse response) {
        store(key, new TurnResult(response, fingerprint));
        inFlight.asMap().remove(key);
        running.asMap().remove(new Key(key.owner(), fingerprint), key.requestId());
    }

    @Override
    public void release(Key key, String fingerprint) {
        inFlight.asMap().remove(key);
        running.asMap().remove(new Key(key.owner(), fingerprint), key.requestId());
    }

    @Override
    public Optional<TurnResult> find(Key key) {
        // Keep this read order. complete() stores the result and then removes the in-flight entry, so a miss here means the result is
        // already stored when it is read below. A hit here may be stale by then, so a stored result wins over the placeholder.
        TurnResult now = inFlight.getIfPresent(key);
        TurnResult done = completed(key);
        return Optional.ofNullable(done != null ? done : now);
    }
}
