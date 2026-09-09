package com.shivamsinha.payauth.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests run against real Postgres, Redis and Kafka in containers.
 *
 * <p>Not mocks. The behaviour under test here — {@code ON CONFLICT DO NOTHING},
 * transaction visibility between concurrent connections, optimistic locking — is
 * behaviour of the database. A mocked repository would assert that our own
 * assumptions are internally consistent, which is exactly the thing worth
 * doubting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.startAll();

        registry.add("spring.datasource.url", IntegrationTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTestContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", IntegrationTestContainers::redisHost);
        registry.add("spring.data.redis.port", IntegrationTestContainers::redisPort);
        registry.add("spring.kafka.bootstrap-servers", IntegrationTestContainers.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void resetState() {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("TRUNCATE TABLE card_authorization, idempotency_key, outbox")
                        .executeUpdate());

        // Velocity counters live in Redis and would otherwise leak between tests: a
        // card that tripped a limit in one test would start the next one already
        // over it.
        try {
            redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                connection.serverCommands().flushDb();
                return null;
            });
        } catch (RuntimeException ex) {
            // Tests that deliberately run without Redis reach this; they assert
            // fail-open behaviour and do not care about its state.
        }
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
