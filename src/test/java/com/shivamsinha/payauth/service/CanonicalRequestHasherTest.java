package com.shivamsinha.payauth.service;

import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRequestHasherTest {

    private final CanonicalRequestHasher hasher = new CanonicalRequestHasher();

    @Test
    @DisplayName("Identical requests hash identically")
    void stableForEqualRequests() {
        AuthorizationRequest a = new AuthorizationRequest("tok_1", 1000L, "INR", "m1", "IN");
        AuthorizationRequest b = new AuthorizationRequest("tok_1", 1000L, "INR", "m1", "IN");

        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("Any material field change changes the hash")
    void sensitiveToEveryField() {
        AuthorizationRequest base = new AuthorizationRequest("tok_1", 1000L, "INR", "m1", "IN");
        String baseHash = hasher.hash(base);

        assertThat(hasher.hash(new AuthorizationRequest("tok_2", 1000L, "INR", "m1", "IN"))).isNotEqualTo(baseHash);
        assertThat(hasher.hash(new AuthorizationRequest("tok_1", 1001L, "INR", "m1", "IN"))).isNotEqualTo(baseHash);
        assertThat(hasher.hash(new AuthorizationRequest("tok_1", 1000L, "USD", "m1", "IN"))).isNotEqualTo(baseHash);
        assertThat(hasher.hash(new AuthorizationRequest("tok_1", 1000L, "INR", "m2", "IN"))).isNotEqualTo(baseHash);
        assertThat(hasher.hash(new AuthorizationRequest("tok_1", 1000L, "INR", "m1", "US"))).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("Canonical form has properties in alphabetical order and no whitespace")
    void canonicalFormIsSorted() {
        AuthorizationRequest request = new AuthorizationRequest("tok_1", 1000L, "INR", "m1", "IN");

        assertThat(hasher.canonicalize(request)).isEqualTo(
                "{\"amountMinor\":1000,\"cardToken\":\"tok_1\",\"countryCode\":\"IN\","
                        + "\"currency\":\"INR\",\"merchantId\":\"m1\"}");
    }

    @Test
    @DisplayName("A null optional field is represented explicitly, so absent and empty cannot collide")
    void nullFieldIsExplicit() {
        AuthorizationRequest withNull = new AuthorizationRequest("tok_1", 1000L, "INR", "m1", null);
        AuthorizationRequest withEmpty = new AuthorizationRequest("tok_1", 1000L, "INR", "m1", "");

        assertThat(hasher.canonicalize(withNull)).contains("\"countryCode\":null");
        assertThat(hasher.hash(withNull)).isNotEqualTo(hasher.hash(withEmpty));
    }

    @Test
    @DisplayName("The hash is a 64 character lowercase hex SHA-256")
    void hashShape() {
        String hash = hasher.hash(new AuthorizationRequest("tok_1", 1000L, "INR", "m1", "IN"));

        assertThat(hash).hasSize(64).matches("^[0-9a-f]{64}$");
    }
}
