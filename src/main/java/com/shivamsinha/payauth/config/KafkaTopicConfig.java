package com.shivamsinha.payauth.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaTopicConfig {

    /**
     * Declared explicitly rather than relying on Boot's {@code KafkaTemplate<?, ?>}.
     *
     * <p>The relay injects a {@code KafkaTemplate<String, String>} and tests replace
     * it with a fault-injecting subclass; both of those want an unambiguous generic
     * type to bind to.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(null));
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Created on startup so a fresh compose stack is usable immediately rather than
     * relying on broker-side auto-creation, which gives you whatever the broker
     * defaults happen to be.
     *
     * <p>Three partitions keyed by authorizationId: events for one authorization
     * always land on the same partition, so a consumer sees them in causal order,
     * while unrelated authorizations still process in parallel.
     */
    @Bean
    public NewTopic authorizationEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
