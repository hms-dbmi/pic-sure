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
 * instance dies simply expires. The result TTL must be at least the conversation idle timeout, so a retry cannot run a turn again while
 * its conversation still holds the first run.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.conversation", name = "store", havingValue = "memory", matchIfMissing = true)
class InMemoryTurnResultStore implements TurnResultStore {

    private final Cache<Key, TurnResult> results;
    /** The {@code requestId} answering each message right now, by {@code Key(owner, fingerprint)}. */
    private final Cache<Key, String> running;

    @Autowired
    InMemoryTurnResultStore(
        @Value("${picsure.ai.conversation.result-ttl}") Duration ttl,
        @Value("${picsure.ai.conversation.idle-timeout}") Duration idleTimeout,
        @Value("${picsure.ai.conversation.max-results}") long maxResults
    ) {
        this(ttl, idleTimeout, maxResults, Ticker.systemTicker());
    }

    InMemoryTurnResultStore(Duration ttl, Duration idleTimeout, long maxResults, Ticker ticker) {
        if (ttl.compareTo(idleTimeout) < 0) {
            throw new IllegalArgumentException(
                "picsure.ai.conversation.result-ttl (" + ttl + ") must be at least idle-timeout (" + idleTimeout
                    + "), or a late retry runs a turn its conversation already holds"
            );
        }
        this.results = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxResults).ticker(ticker).build();
        this.running = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxResults).ticker(ticker).build();
    }

    @Override
    public Claim claim(Key key, String fingerprint) {
        TurnResult mine = new TurnResult(null, fingerprint);
        TurnResult existing = results.asMap().putIfAbsent(key, mine);
        if (existing != null) {
            return new Claim.Existing(existing);
        }
        String other = running.asMap().putIfAbsent(new Key(key.owner(), fingerprint), key.requestId());
        if (other != null) {
            results.asMap().remove(key, mine);
            return new Claim.Duplicate(other);
        }
        return new Claim.Granted();
    }

    @Override
    public void complete(Key key, String fingerprint, ChatResponse response) {
        results.put(key, new TurnResult(response, fingerprint));
        running.asMap().remove(new Key(key.owner(), fingerprint), key.requestId());
    }

    @Override
    public void release(Key key, String fingerprint) {
        results.asMap().computeIfPresent(key, (k, stored) -> stored.running() ? null : stored);
        running.asMap().remove(new Key(key.owner(), fingerprint), key.requestId());
    }

    @Override
    public Optional<TurnResult> find(Key key) {
        return Optional.ofNullable(results.getIfPresent(key));
    }
}
