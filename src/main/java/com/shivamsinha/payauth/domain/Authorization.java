package com.shivamsinha.payauth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * The authorization aggregate.
 *
 * <p>{@code amountMinor} is a {@code long} of currency minor units (paise, cents).
 * There is no {@code double}, {@code float} or {@code BigDecimal} anywhere in the
 * money path: minor units are exact, and every arithmetic operation on them is
 * exact too.
 *
 * <p>{@code version} drives JPA optimistic locking. Capture and void both mutate
 * a row that a retry or a parallel operator action may be mutating at the same
 * time; the version check turns that race into a 409 rather than a lost update.
 */
@Entity
@Table(name = "card_authorization")
public class Authorization {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "card_token", nullable = false, length = 64)
    private String cardToken;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    // CHAR(3), not VARCHAR: an ISO 4217 code is exactly three characters, and the
    // fixed width lets the database reject a malformed one for us.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AuthorizationStatus status;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "response_code", nullable = false, length = 2, columnDefinition = "char(2)")
    private String responseCode;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Authorization() {
        // JPA
    }

    public Authorization(UUID id,
                         String cardToken,
                         long amountMinor,
                         String currency,
                         String merchantId,
                         AuthorizationStatus status,
                         ResponseCode responseCode,
                         Instant createdAt) {
        this.id = id;
        this.cardToken = cardToken;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.merchantId = merchantId;
        this.status = status;
        this.responseCode = responseCode.code();
        this.createdAt = createdAt;
    }

    public void capture() {
        if (status != AuthorizationStatus.APPROVED) {
            throw new IllegalStateException(
                    "Only an APPROVED authorization can be captured, was " + status);
        }
        this.status = AuthorizationStatus.CAPTURED;
    }

    public void voidAuthorization() {
        if (status != AuthorizationStatus.APPROVED) {
            throw new IllegalStateException(
                    "Only an APPROVED authorization can be voided, was " + status);
        }
        this.status = AuthorizationStatus.VOIDED;
    }

    public UUID getId() {
        return id;
    }

    public String getCardToken() {
        return cardToken;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public AuthorizationStatus getStatus() {
        return status;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
