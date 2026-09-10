package com.shivamsinha.payauth.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The demo console.
 *
 * <p>Off by a single flag, and it should be off anywhere real. These endpoints
 * read raw authorization rows and per-card velocity state, which is exactly the
 * data you would not expose without authentication in production. It exists so the
 * mechanics of the system are visible while explaining or demonstrating it.
 */
@ConfigurationProperties(prefix = "payauth.console")
public record ConsoleProperties(boolean enabled) {
}
