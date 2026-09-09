package com.shivamsinha.payauth.velocity;

import com.shivamsinha.payauth.config.VelocityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RulesConfigLoaderTest {

    @Test
    @DisplayName("The packaged rules.yml parses into the shipped defaults")
    void loadsPackagedRules() {
        RulesConfigLoader loader = new RulesConfigLoader(
                new DefaultResourceLoader(),
                new VelocityProperties(true, "classpath:rules.yml", Duration.ofSeconds(10), Duration.ofMillis(200)));

        RulesConfig config = loader.current();

        assertThat(config.cardVelocity().enabled()).isTrue();
        assertThat(config.cardVelocity().maxAuthorizations()).isEqualTo(5);
        assertThat(config.cardVelocity().windowSeconds()).isEqualTo(60);
        assertThat(config.cardVelocity().verdict()).isEqualTo(Verdict.DECLINE);
        assertThat(config.amountAnomaly().multiplier()).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(config.amountAnomaly().verdict()).isEqualTo(Verdict.REVIEW);
        assertThat(config.impossibleTravel().windowSeconds()).isEqualTo(600);
        assertThat(config.impossibleTravel().minDistanceKm()).isEqualTo(500);
    }

    @Test
    @DisplayName("Editing the file and reloading swaps the thresholds")
    void hotReloadPicksUpChanges(@TempDir Path tempDir) throws IOException {
        Path rules = tempDir.resolve("rules.yml");
        Files.writeString(rules, """
                card-velocity:
                  enabled: true
                  max-authorizations: 5
                  window-seconds: 60
                  verdict: DECLINE
                """);

        RulesConfigLoader loader = loaderFor(rules);
        assertThat(loader.current().cardVelocity().maxAuthorizations()).isEqualTo(5);

        // An operator tightens the limit during an attack.
        Files.writeString(rules, """
                card-velocity:
                  enabled: true
                  max-authorizations: 2
                  window-seconds: 30
                  verdict: REVIEW
                """);
        loader.reload();

        assertThat(loader.current().cardVelocity().maxAuthorizations()).isEqualTo(2);
        assertThat(loader.current().cardVelocity().windowSeconds()).isEqualTo(30);
        assertThat(loader.current().cardVelocity().verdict()).isEqualTo(Verdict.REVIEW);
    }

    @Test
    @DisplayName("A malformed file is ignored and the last good configuration stays in force")
    void malformedFileIsIgnored(@TempDir Path tempDir) throws IOException {
        Path rules = tempDir.resolve("rules.yml");
        Files.writeString(rules, """
                card-velocity:
                  enabled: true
                  max-authorizations: 7
                """);

        RulesConfigLoader loader = loaderFor(rules);
        assertThat(loader.current().cardVelocity().maxAuthorizations()).isEqualTo(7);

        Files.writeString(rules, "card-velocity: [ this is not: valid yaml\n");
        loader.reload();

        assertThat(loader.current().cardVelocity().maxAuthorizations())
                .as("a YAML typo must not be able to change how payments are screened")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("A missing section falls back to defaults rather than nulls")
    void missingSectionsGetDefaults(@TempDir Path tempDir) throws IOException {
        Path rules = tempDir.resolve("rules.yml");
        Files.writeString(rules, """
                card-velocity:
                  enabled: false
                  max-authorizations: 9
                  window-seconds: 120
                  verdict: REVIEW
                """);

        RulesConfig config = loaderFor(rules).current();

        assertThat(config.amountAnomaly()).isNotNull();
        assertThat(config.amountAnomaly().multiplier()).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(config.impossibleTravel()).isNotNull();
        assertThat(config.impossibleTravel().minDistanceKm()).isEqualTo(500);
    }

    @Test
    @DisplayName("A missing file leaves the defaults in place instead of failing startup")
    void missingFileKeepsDefaults(@TempDir Path tempDir) {
        RulesConfigLoader loader = loaderFor(tempDir.resolve("does-not-exist.yml"));

        assertThat(loader.current().cardVelocity().maxAuthorizations()).isEqualTo(5);
    }

    private RulesConfigLoader loaderFor(Path rules) {
        return new RulesConfigLoader(
                new DefaultResourceLoader(),
                new VelocityProperties(true, "file:" + rules.toAbsolutePath(),
                        Duration.ofSeconds(10), Duration.ofMillis(200)));
    }
}
