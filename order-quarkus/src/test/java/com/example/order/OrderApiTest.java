package com.example.order;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class OrderApiTest {

    @Test
    void createsConfirmsAndCancelsOrder() {
        int stockBefore = given()
                .get("/api/products")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getInt("find { it.code == 'P002' }.stock");

        long orderId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "customerCode": "C001",
                          "lines": [
                            {"productCode": "P002", "quantity": 2}
                          ]
                        }
                        """)
                .post("/api/orders")
                .then()
                .statusCode(201)
                .body("status", is("DRAFT"))
                .body("totalAmount", is(6000.0f))
                .extract()
                .jsonPath()
                .getLong("id");

        given()
                .post("/api/orders/{id}/confirm", orderId)
                .then()
                .statusCode(200)
                .body("status", is("CONFIRMED"));

        given()
                .get("/api/products")
                .then()
                .body("find { it.code == 'P002' }.stock", is(stockBefore - 2));

        given()
                .post("/api/orders/{id}/cancel", orderId)
                .then()
                .statusCode(200)
                .body("status", is("CANCELLED"));

        given()
                .get("/api/products")
                .then()
                .body("find { it.code == 'P002' }.stock", is(stockBefore));
    }

    @Test
    void rejectsOrderWithoutLines() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"customerCode": "C001", "lines": []}
                        """)
                .post("/api/orders")
                .then()
                .statusCode(400);
    }

    @Test
    void returnsNotFoundForUnknownCustomer() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"customerCode": "NOPE", "lines": [{"productCode": "P001", "quantity": 1}]}
                        """)
                .post("/api/orders")
                .then()
                .statusCode(404);
    }
}
