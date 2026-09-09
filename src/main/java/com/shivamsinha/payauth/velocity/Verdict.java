package com.shivamsinha.payauth.velocity;

/**
 * A rule's opinion, ordered from most permissive to most restrictive.
 *
 * <p>The engine takes the strictest verdict any rule returns, so the ordinal order
 * here is load-bearing: reorder these constants and the engine silently changes
 * meaning.
 */
public enum Verdict {

    ALLOW,
    REVIEW,
    DECLINE;

    public Verdict strictest(Verdict other) {
        return this.compareTo(other) >= 0 ? this : other;
    }
}
