package com.lqx.ragmcp.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One pgvector container shared by the Phase 5 MCP integration test classes. Each Spring context
 * still runs Flyway; the container is started once per JVM.
 */
public final class McpTestDatabase {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("ragdb")
                    .withUsername("rag")
                    .withPassword("rag_local_pw");

    static {
        POSTGRES.start();
    }

    private McpTestDatabase() {
    }
}
