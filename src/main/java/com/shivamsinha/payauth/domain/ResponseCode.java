package com.shivamsinha.payauth.domain;

/**
 * ISO 8583 field 39 response codes. Only the handful this service can emit.
 */
public enum ResponseCode {

    APPROVED("00"),
    DO_NOT_HONOUR("05"),
    INSUFFICIENT_FUNDS("51");

    private final String code;

    ResponseCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ResponseCode fromCode(String code) {
        for (ResponseCode value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown ISO 8583 response code: " + code);
    }
}
