package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The turn result store follows the conversation store only if Spring links them, which the unit tests wire by hand. */
class ConversationStoresWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
        .withUserConfiguration(InMemoryConversationStore.class, InMemoryTurnResultStore.class).withPropertyValues(
            "picsure.ai.conversation.idle-timeout=60m", "picsure.ai.conversation.max-turns=50",
            "picsure.ai.conversation.max-total-chars=1000000", "picsure.ai.conversation.max-conversations-per-owner=2",
            "picsure.ai.conversation.max-owners=1", "picsure.ai.conversation.result-ttl=60m",
            "picsure.ai.conversation.max-results-per-owner=100"
        );

    @Test
    void springLinksTheResultStoreToTheConversationStore() {
        runner.run(context -> {
            ConversationStore conversations = context.getBean(ConversationStore.class);
            TurnResultStore results = context.getBean(TurnResultStore.class);
            TurnResultStore.Key key = new TurnResultStore.Key("owner-a", "req-1");
            ConversationTurn turn = new ConversationTurn("hello", "reply", java.util.List.of(), null);
            conversations.append(new ConversationStore.Key("owner-a", "conv-1"), turn);
            results.claim(key, "print");
            results.complete(key, "print", ChatResponse.textOnly("Hello"));

            conversations.append(new ConversationStore.Key("owner-b", "conv-1"), turn);

            assertTrue(results.find(key).isEmpty());
        });
    }
}
