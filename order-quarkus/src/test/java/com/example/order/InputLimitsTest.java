package com.example.order;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Input limits from {@code order-api.yaml} ({@code maxLength}, {@code maxItems},
 * {@code maximum}) are generated into Bean Validation annotations, so oversized
 * requests are rejected with 400 before any business logic runs.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class InputLimitsTest extends AuthenticatedTest {

    private static final String LONG_PASSWORD = "x".repeat(129);

    @Test
    void rejectsPasswordLongerThan128Characters() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"user","password":"%s"}
                        """.formatted(LONG_PASSWORD))
                .post("/api/auth/login")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsUsernameLongerThan50Characters() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"%s","password":"user"}
                        """.formatted("u".repeat(51)))
                .post("/api/auth/login")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsMoreThan100OrderLines() {
        String lines = IntStream.range(0, 101)
                .mapToObj(index -> """
                        {"productCode":"P001","quantity":1}""")
                .collect(Collectors.joining(",", "[", "]"));

        api()
                .contentType(ContentType.JSON)
                .body("""
                        {"customerCode":"C001","lines":%s}
                        """.formatted(lines))
                .post("/api/orders")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsQuantityAbove10000() {
        api()
                .contentType(ContentType.JSON)
                .body("""
                        {"customerCode":"C001","lines":[{"productCode":"P001","quantity":10001}]}
                        """)
                .post("/api/orders")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsProductCodeLongerThan20Characters() {
        api()
                .contentType(ContentType.JSON)
                .body("""
                        {"customerCode":"C001","lines":[{"productCode":"%s","quantity":1}]}
                        """.formatted("P".repeat(21)))
                .post("/api/orders")
                .then()
                .statusCode(400)
                .body("message", containsString("20"));
    }
}
