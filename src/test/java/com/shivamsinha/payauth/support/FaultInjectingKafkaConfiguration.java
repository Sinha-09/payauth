package com.shivamsinha.payauth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@code KafkaTemplate} that can be told to fail after N sends.
 *
 * <p>Used to reproduce a relay that dies part way through a batch. The broker
 * itself is a real container; only the moment of failure is injected, because
 * "kill the broker between message one and message two" is not something a test
 * can time reliably.
 */
@TestConfiguration
public class FaultInjectingKafkaConfiguration {

    @Bean
    @Primary
    public FaultInjectingKafkaTemplate faultInjectingKafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new FaultInjectingKafkaTemplate(producerFactory);
    }

    public static class FaultInjectingKafkaTemplate extends KafkaTemplate<String, String> {

        public static class InjectedBrokerFailure extends RuntimeException {
            InjectedBrokerFailure(String message) {
                super(message);
            }
        }

        private final AtomicInteger sends = new AtomicInteger();
        private volatile int failAfter = Integer.MAX_VALUE;

        public FaultInjectingKafkaTemplate(ProducerFactory<String, String> producerFactory) {
            super(producerFactory);
        }

        /** Allow {@code n} successful sends, then throw on every send after that. */
        public void failAfterSends(int n) {
            sends.set(0);
            this.failAfter = n;
        }

        public void healBroker() {
            this.failAfter = Integer.MAX_VALUE;
        }

        public int sendCount() {
            return sends.get();
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            if (sends.incrementAndGet() > failAfter) {
                throw new InjectedBrokerFailure("simulated broker outage on send #" + sends.get());
            }
            return super.send(topic, key, data);
        }
    }
}
