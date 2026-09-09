package com.shivamsinha.payauth.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
    void resetDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("TRUNCATE TABLE card_authorization, idempotency_key, outbox").executeUpdate();
        });
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
