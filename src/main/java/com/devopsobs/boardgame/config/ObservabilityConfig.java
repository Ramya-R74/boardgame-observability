package com.devopsobs.boardgame.config;

import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.config.MeterFilter;

@Configuration
public class ObservabilityConfig {

    /** Enables the @Observed annotation on service methods. */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }

    /**
     * Common tags applied to EVERY meter. These are the fields you will group by in
     * Kibana, so they must match the ECS fields the logs carry (service.name,
     * service.version, service.environment) or correlation breaks.
     */
    @Bean
    MeterFilter commonTags(@Value("${spring.application.name}") String service,
                           @Value("${info.app.version:unknown}") String version,
                           @Value("${DEPLOY_ENV:local}") String env) {
        return MeterFilter.commonTags(io.micrometer.core.instrument.Tags.of(
                "service.name", service,
                "service.version", version,
                "service.environment", env));
    }

    /**
     * Cardinality guard. Any meter that somehow gains more than 100 tag
     * combinations gets denied rather than silently poisoning the backend.
     */
    @Bean
    MeterFilter cardinalityLimit() {
        return MeterFilter.maximumAllowableTags(
                "http.server.requests", "uri", 100, MeterFilter.deny());
    }
}
