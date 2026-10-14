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

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-mcp-e2e;mode=mysql;non_keywords=value",
        "server.address=127.0.0.1", "investbook.agent.enabled=true", "investbook.agent.token=" + AgentFixture.TOKEN,
        "investbook.agent.accounts.main=PRIVATE-BROKER-A", "investbook.agent.accounts.second=PRIVATE-BROKER-B",
        "investbook.agent.accounts.empty=PRIVATE-EMPTY", "investbook.agent.accounts.fx=PRIVATE-FX"})
@EnabledIfEnvironmentVariable(named="INVESTBOOK_MCP_PYTHON", matches=".+")
class AgentMcpIntegrationTest {
    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @Test void realStdioClientCallsAllToolsAgainstJavaDatabase() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(_ -> AgentFixture.populate(em));
        ProcessBuilder builder = new ProcessBuilder(System.getenv("INVESTBOOK_MCP_PYTHON"),
                "integrations/investbook-mcp/tests/e2e_client.py");
        builder.environment().put("INVESTBOOK_BASE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("INVESTBOOK_AGENT_TOKEN", AgentFixture.TOKEN);
        Path log = Path.of("target", "mcp-e2e.log");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "MCP client timed out");
            assertEquals(0, process.exitValue(), "See target/mcp-e2e.log (synthetic data only)");
        } finally {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }
    }
}
