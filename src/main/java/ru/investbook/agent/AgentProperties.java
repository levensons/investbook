/*
 * InvestBook
 * Copyright (C) 2026  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package ru.investbook.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties("investbook.agent")
public class AgentProperties {
    private boolean enabled = false;
    private String token = "";
    // Public aliases -> private database identifiers. Never serialize the values.
    private Map<String, String> accounts = new LinkedHashMap<>();
    private ZoneId timezone = ZoneId.of("Europe/Moscow");

    public void validate() {
        if (!enabled) return;
        if (!token.matches("[A-Za-z0-9_-]{32,256}") || accounts.isEmpty() || accounts.size() > 20
                || accounts.keySet().stream().anyMatch(a -> !a.matches("[a-zA-Z0-9_-]{1,64}"))
                || accounts.values().stream().anyMatch(String::isBlank)
                || accounts.values().stream().distinct().count() != accounts.size()) {
            throw new IllegalStateException("Agent API requires a strong token and 1..20 unique account aliases");
        }
    }
}
