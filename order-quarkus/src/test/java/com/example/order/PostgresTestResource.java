package com.example.order;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Starts a real PostgreSQL server from Maven-provided native binaries.
 * No Docker daemon or container runtime is required.
 *
 * The binaries are resolved by the embedded-postgres-binaries BOM and
 * extracted under /tmp/embedded-pg on first use.
 */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private EmbeddedPostgres postgres;

    @Override
    public Map<String, String> start() {
        try {
            postgres = EmbeddedPostgres.builder()
                    .setPort(0)
                    .start();

            return Map.of(
                    "quarkus.datasource.db-kind", "postgresql",
                    "quarkus.datasource.username", "postgres",
                    "quarkus.datasource.password", "postgres",
                    "quarkus.datasource.jdbc.url", postgres.getJdbcUrl("postgres", "postgres")
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start embedded PostgreSQL", exception);
        }
    }

    @Override
    public void stop() {
        if (postgres == null) {
            return;
        }
        try {
            postgres.close();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
