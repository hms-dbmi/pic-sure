package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.time.Duration;
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
 * conversation still holds the first run. <p> Results are bounded per caller, never across callers, so one caller cycling through
 * {@code requestId}s can only push out their own replies, not another caller's. Running claims need no size bound: each is removed when its
 * turn completes or fails, and an orphan expires with the instance or the TTL.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.conversation", name = "store", havingValue = "memory", matchIfMissing = true)
class InMemoryTurnResultStore implements TurnResultStore {

    /** Each caller's turn results by {@code requestId}. An idle caller's bucket is dropped once all its results have expired. */
    private final Cache<String, Cache<String, TurnResult>> results;
    private final Duration ttl;
    private final long maxResultsPerOwner;
    private final Ticker ticker;
    /** The {@code requestId} answering each message right now, by {@code Key(owner, fingerprint)}. */
    private final Cache<Key, String> running;

    @Autowired
    InMemoryTurnResultStore(
        @Value("${picsure.ai.conversation.result-ttl}") Duration ttl,
        @Value("${picsure.ai.conversation.idle-timeout}") Duration idleTimeout,
        @Value("${picsure.ai.conversation.max-results-per-owner}") long maxResultsPerOwner
    ) {
        this(ttl, idleTimeout, maxResultsPerOwner, Ticker.systemTicker());
    }

    InMemoryTurnResultStore(Duration ttl, Duration idleTimeout, long maxResultsPerOwner, Ticker ticker) {
        if (ttl.compareTo(idleTimeout) < 0) {
            throw new IllegalArgumentException(
                "picsure.ai.conversation.result-ttl (" + ttl + ") must be at least idle-timeout (" + idleTimeout
                    + "), or a late retry runs a turn its conversation already holds"
            );
        }
        this.ttl = ttl;
        this.maxResultsPerOwner = maxResultsPerOwner;
        this.ticker = ticker;
        this.results = Caffeine.newBuilder().expireAfterAccess(ttl).ticker(ticker).build();
        this.running = Caffeine.newBuilder().expireAfterWrite(ttl).ticker(ticker).build();
    }

    private Cache<String, TurnResult> bucket(String owner) {
        return results.get(
            owner,
            o -> Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxResultsPerOwner).executor(Runnable::run).ticker(ticker).build()
        );
    }

    @Override
    public Claim claim(Key key, String fingerprint) {
        TurnResult mine = new TurnResult(null, fingerprint);
        TurnResult existing = bucket(key.owner()).asMap().putIfAbsent(key.requestId(), mine);
        if (existing != null) {
            return new Claim.Existing(existing);
        }
        String other = running.asMap().putIfAbsent(new Key(key.owner(), fingerprint), key.requestId());
        if (other != null) {
            bucket(key.owner()).asMap().remove(key.requestId(), mine);
            return new Claim.Duplicate(other);
        }
        return new Claim.Granted();
    }

    @Override
    public void complete(Key key, String fingerprint, ChatResponse response) {
        bucket(key.owner()).put(key.requestId(), new TurnResult(response, fingerprint));
        running.asMap().remove(new Key(key.owner(), fingerprint), key.requestId());
    }

    @Override
    public void release(Key key, String fingerprint) {
        bucket(key.owner()).asMap().computeIfPresent(key.requestId(), (k, stored) -> stored.running() ? null : stored);
        running.asMap().remove(new Key(key.owner(), fingerprint), key.requestId());
    }

    @Override
    public Optional<TurnResult> find(Key key) {
        Cache<String, TurnResult> owned = results.getIfPresent(key.owner());
        return Optional.ofNullable(owned == null ? null : owned.getIfPresent(key.requestId()));
    }
}
