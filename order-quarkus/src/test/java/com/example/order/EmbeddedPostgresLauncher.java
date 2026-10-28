package com.example.order;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;

/**
 * Local PostgreSQL without Docker.
 *
 * Run with:
 *   mvn -pl order-quarkus -Pembedded-postgres test-compile exec:java
 *
 * The process stays in the foreground. Stop it with Ctrl+C.
 */
public final class EmbeddedPostgresLauncher {

    private EmbeddedPostgresLauncher() {
    }

    public static void main(String[] args) throws Exception {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(5432)
                .setRegisterShutdownHook(false)
                .start();

        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE orders LOGIN PASSWORD 'orders'");
            statement.execute("CREATE DATABASE orders OWNER orders");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (Exception ignored) {
                // JVM is shutting down.
            }
        }));

        System.out.println("Embedded PostgreSQL started: jdbc:postgresql://localhost:5432/orders");
        System.out.println("user=orders password=orders  (Ctrl+C to stop)");
        new CountDownLatch(1).await();
    }
}
