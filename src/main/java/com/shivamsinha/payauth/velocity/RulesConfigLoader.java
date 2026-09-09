package com.shivamsinha.payauth.velocity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.shivamsinha.payauth.config.VelocityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads {@code rules.yml} and re-reads it on a schedule.
 *
 * <p>Reload is deliberately conservative. The new file is parsed into a complete
 * {@link RulesConfig} before anything is swapped, and the swap is a single
 * reference assignment, so an evaluation in flight sees either the whole old
 * configuration or the whole new one — never a half-applied mixture of the two.
 *
 * <p>A file that fails to parse is logged and discarded. Operators tune these
 * thresholds under pressure, and a typo must not be able to take the payment path
 * with it.
 */
@Component
public class RulesConfigLoader implements RulesConfigSource {

    private static final Logger log = LoggerFactory.getLogger(RulesConfigLoader.class);

    private final ResourceLoader resourceLoader;
    private final VelocityProperties properties;
    private final ObjectMapper yamlMapper;

    private final AtomicReference<RulesConfig> current = new AtomicReference<>(RulesConfig.defaults());
    private volatile byte[] lastLoadedBytes = new byte[0];

    public RulesConfigLoader(ResourceLoader resourceLoader, VelocityProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        reload();
    }

    @Override
    public RulesConfig current() {
        return current.get();
    }

    @Scheduled(
            fixedDelayString = "${payauth.velocity.refresh-interval:10s}",
            initialDelayString = "${payauth.velocity.refresh-interval:10s}")
    public void reload() {
        Resource resource = resourceLoader.getResource(properties.rulesFile());
        if (!resource.exists()) {
            log.warn("Velocity rules file {} does not exist; keeping the current configuration",
                    properties.rulesFile());
            return;
        }

        byte[] bytes;
        try (InputStream in = resource.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException ex) {
            log.warn("Could not read velocity rules from {}; keeping the current configuration",
                    properties.rulesFile(), ex);
            return;
        }

        if (Arrays.equals(bytes, lastLoadedBytes)) {
            return;
        }

        RulesConfig parsed;
        try {
            parsed = yamlMapper.readValue(bytes, RulesConfig.class);
        } catch (IOException ex) {
            log.error("Velocity rules at {} are not valid; keeping the current configuration",
                    properties.rulesFile(), ex);
            return;
        }

        lastLoadedBytes = bytes;
        current.set(parsed);
        log.info("Loaded velocity rules from {}: cardVelocity={}, amountAnomaly={}, impossibleTravel={}",
                properties.rulesFile(), parsed.cardVelocity(), parsed.amountAnomaly(), parsed.impossibleTravel());
    }
}
