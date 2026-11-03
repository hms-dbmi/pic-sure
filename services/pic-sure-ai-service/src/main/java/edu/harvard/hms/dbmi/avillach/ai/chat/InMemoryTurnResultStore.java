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
 * instance dies simply expires. The default TTL matches the conversation idle timeout, so a retry cannot run a turn again while its
 * conversation still holds the first run.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.conversation", name = "store", havingValue = "memory", matchIfMissing = true)
class InMemoryTurnResultStore implements TurnResultStore {

    private final Cache<Key, TurnResult> results;

    @Autowired
    InMemoryTurnResultStore(
        @Value("${picsure.ai.conversation.result-ttl:60m}") Duration ttl,
        @Value("${picsure.ai.conversation.max-results:10000}") long maxResults
    ) {
        this(ttl, maxResults, Ticker.systemTicker());
    }

    InMemoryTurnResultStore(Duration ttl, long maxResults, Ticker ticker) {
        this.results = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxResults).ticker(ticker).build();
    }

    @Override
    public Optional<TurnResult> claim(Key key) {
        return Optional.ofNullable(results.asMap().putIfAbsent(key, TurnResult.RUNNING));
    }

    @Override
    public void complete(Key key, ChatResponse response) {
        results.put(key, new TurnResult(response));
    }

    @Override
    public void release(Key key) {
        results.asMap().remove(key, TurnResult.RUNNING);
    }

    @Override
    public Optional<TurnResult> find(Key key) {
        return Optional.ofNullable(results.getIfPresent(key));
    }
}
