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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class AgentDisabledTest {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy security;
    @Test void disabledByDefaultEvenWithToken() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).addFilters(security).build()
                .perform(get("/api/agent/v1/accounts").header("Authorization", "Bearer " + AgentFixture.TOKEN))
                .andExpect(status().isNotFound());
    }
    @Test void enablingWithoutStrongSecretAndExplicitScopeFailsClosed() {
        var config = new AgentProperties(); config.setEnabled(true);
        assertThrows(IllegalStateException.class, config::validate);
        config.setToken(AgentFixture.TOKEN);
        assertThrows(IllegalStateException.class, config::validate);
    }
}
