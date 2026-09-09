package com.shivamsinha.payauth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param amountMinor currency minor units (paise, cents) as a {@code long}.
 *                    Never a decimal type: 1999 is unambiguous, 19.99 is not.
 * @param countryCode ISO 3166-1 alpha-2 country the authorization originated in.
 *                    Optional; consumed by the impossible-travel velocity rule.
 */
public record AuthorizationRequest(

        @NotBlank(message = "cardToken is required")
        @Size(max = 64, message = "cardToken must be at most 64 characters")
        String cardToken,

        @Positive(message = "amountMinor must be a positive number of minor units")
        long amountMinor,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO 4217 alpha-3 code")
        String currency,

        @NotBlank(message = "merchantId is required")
        @Size(max = 64, message = "merchantId must be at most 64 characters")
        String merchantId,

        @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode must be an ISO 3166-1 alpha-2 code")
        String countryCode) {
}
