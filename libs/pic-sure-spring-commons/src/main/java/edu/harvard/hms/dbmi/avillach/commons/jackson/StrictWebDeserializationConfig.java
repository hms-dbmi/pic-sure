package edu.harvard.hms.dbmi.avillach.commons.jackson;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Makes every PIC-SURE service that depends on {@code pic-sure-spring-commons} reject request bodies carrying properties its contract does
 * not model, instead of silently discarding them.
 *
 * <p>Lenient binding is how a client typo, a stale field name, or a renamed property turns into a 200 with the value quietly dropped;
 * strictness converts all three into an immediate 400 that names the offending field. This is deliberately ONE uniform mechanism rather
 * than a per-service or per-DTO setting: a service that has to remember to opt in is a service that will forget.
 *
 * <p>Registered through {@code AutoConfiguration.imports} -- NOT through a per-application {@code @Import} the way
 * {@code GatewayExceptionAdvice} is -- precisely so no application has to remember anything. It customizes Boot's
 * {@code Jackson2ObjectMapperBuilder}, so it reaches the auto-configured primary {@link ObjectMapper}: the one behind Spring MVC
 * {@code @RequestBody} binding, and behind any {@code RestClient}/{@code WebClient} that shares it.
 *
 * <p>Individual types opt out with {@code @JsonIgnoreProperties(ignoreUnknown = true)}; the feature is a default, not an override.
 * Contracts that read payloads we do not own -- PSAMA's introspection response, audit intake -- do exactly that on purpose.
 */
@AutoConfiguration(before = JacksonAutoConfiguration.class)
@ConditionalOnClass({ObjectMapper.class, Jackson2ObjectMapperBuilderCustomizer.class})
public class StrictWebDeserializationConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictWebDeserialization() {
        return builder -> builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
