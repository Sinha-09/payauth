package com.shivamsinha.payauth.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton containers.
 *
 * <p>Started once per JVM and shared by every integration test, rather than
 * per-class via {@code @Testcontainers}. Postgres and Kafka each cost several
 * seconds to boot; paying that once keeps the suite fast enough that people
 * actually run it before pushing.
 *
 * <p>They are never stopped: Ryuk (Testcontainers' reaper sidecar) removes them
 * when the JVM exits.
 */
public final class IntegrationTestContainers {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("payauth")
                    .withUsername("payauth")
                    .withPassword("payauth")
                    .withReuse(false);

    @SuppressWarnings("resource")
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no");

    // apache/kafka:4.0.0, not 3.9.0: on 3.9.0 the image's entrypoint formats storage
    // before Testcontainers has written the advertised listeners, and the broker dies
    // with "advertised.listeners cannot use the nonroutable meta-address 0.0.0.0".
    public static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    private static boolean started;

    private IntegrationTestContainers() {
    }

    public static synchronized void startAll() {
        if (started) {
            return;
        }
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        started = true;
    }

    public static String redisHost() {
        return REDIS.getHost();
    }

    public static int redisPort() {
        return REDIS.getMappedPort(6379);
    }
}
