package com.shivamsinha.payauth.velocity;

import java.util.Map;

/**
 * @param verdict  the strictest verdict any rule returned
 * @param perRule  what each rule said, for logging and for explaining a decline
 * @param degraded true when the verdict was reached without Redis, i.e. failed open
 */
public record RuleEvaluation(Verdict verdict, Map<String, Verdict> perRule, boolean degraded) {

    public static RuleEvaluation allowedByDefault() {
        return new RuleEvaluation(Verdict.ALLOW, Map.of(), true);
    }
}
