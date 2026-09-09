package com.shivamsinha.payauth.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountryGeographyTest {

    @Test
    @DisplayName("Known distances are in the right ballpark")
    void distancesAreSane() {
        assertThat(CountryGeography.distanceKm("IN", "US")).get()
                .satisfies(km -> assertThat(km).isBetween(12_000d, 14_000d));
        assertThat(CountryGeography.distanceKm("FR", "DE")).get()
                .satisfies(km -> assertThat(km).isBetween(400d, 900d));
        assertThat(CountryGeography.distanceKm("IN", "IN")).get()
                .satisfies(km -> assertThat(km).isZero());
    }

    @Test
    @DisplayName("Distance is symmetric")
    void symmetric() {
        assertThat(CountryGeography.distanceKm("SG", "BR"))
                .isEqualTo(CountryGeography.distanceKm("BR", "SG"));
    }

    @Test
    @DisplayName("An unknown country yields no distance rather than a wrong one")
    void unknownCountry() {
        assertThat(CountryGeography.distanceKm("IN", "ZZ")).isEmpty();
        assertThat(CountryGeography.distanceKm(null, "IN")).isEmpty();
        assertThat(CountryGeography.isKnown("ZZ")).isFalse();
        assertThat(CountryGeography.isKnown("IN")).isTrue();
    }
}
