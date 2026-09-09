package com.shivamsinha.payauth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over a canonical serialization of the request.
 *
 * <p>The point of the canonical form is that two requests which mean the same
 * thing must hash the same. A client that retries with its JSON fields in a
 * different order, or with different whitespace, is retrying — not sending a new
 * request — and must not be told 409.
 *
 * <p>Canonical form here is: the DTO serialized with properties sorted
 * alphabetically, no insignificant whitespace, nulls included explicitly so that
 * an absent field and an explicit null cannot collide.
 *
 * <p>Note that we hash the parsed DTO, not the raw bytes. Hashing raw bytes would
 * make key ordering and whitespace significant, which is exactly what we are
 * trying to avoid.
 */
@Component
public class CanonicalRequestHasher {

    private final ObjectMapper canonicalMapper;

    public CanonicalRequestHasher() {
        this.canonicalMapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    public String hash(Object request) {
        return sha256Hex(canonicalize(request));
    }

    String canonicalize(Object request) {
        try {
            return canonicalMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Request could not be canonicalized", ex);
        }
    }

    private String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
