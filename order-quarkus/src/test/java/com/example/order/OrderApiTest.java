package com.example.order;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class OrderApiTest extends AuthenticatedTest {

    @Test
    void createsConfirmsAndCancelsOrder() {
        int stockBefore = api()
                .get("/api/products")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getInt("content.find { it.code == 'P002' }.stock");

        Response created = api()
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
                .response();

        long orderId = created.jsonPath().getLong("id");
        assertThat(created.header("Location"), endsWith("/api/orders/" + orderId));
        assertThat(created.header("Location"), not(containsString("/api/api/")));

        api()
                .post("/api/orders/{id}/confirm", orderId)
                .then()
                .statusCode(200)
                .body("status", is("CONFIRMED"));

        api()
                .get("/api/products")
                .then()
                .body("content.find { it.code == 'P002' }.stock", is(stockBefore - 2));

        api()
                .post("/api/orders/{id}/cancel", orderId)
                .then()
                .statusCode(200)
                .body("status", is("CANCELLED"));

        api()
                .get("/api/products")
                .then()
                .body("content.find { it.code == 'P002' }.stock", is(stockBefore));
    }

    @Test
    void rejectsDuplicateLinesThatExceedStock() {
        int stockBefore = api()
                .get("/api/products")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getInt("content.find { it.code == 'P003' }.stock");
        assumeTrue(stockBefore >= 2, "P003 の在庫が少ないためスキップ");

        int quantityPerLine = stockBefore / 2 + 1;
        long orderId = api()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "customerCode": "C001",
                          "lines": [
                            {"productCode": "P003", "quantity": %d},
                            {"productCode": "P003", "quantity": %d}
                          ]
                        }
                        """.formatted(quantityPerLine, quantityPerLine))
                .post("/api/orders")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getLong("id");

        api()
                .post("/api/orders/{id}/confirm", orderId)
                .then()
                .statusCode(409);

        api()
                .get("/api/products")
                .then()
                .body("content.find { it.code == 'P003' }.stock", is(stockBefore));
    }

    @Test
    void rejectsOrderWithoutLines() {
        api()
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
        api()
                .contentType(ContentType.JSON)
                .body("""
                        {"customerCode": "NOPE", "lines": [{"productCode": "P001", "quantity": 1}]}
                        """)
                .post("/api/orders")
                .then()
                .statusCode(404);
    }

    @Test
    void paginatesOrders() {
        api()
                .queryParam("page", 0)
                .queryParam("size", 2)
                .get("/api/orders")
                .then()
                .statusCode(200)
                .body("content.size()", is(2))
                .body("page", is(0))
                .body("size", is(2))
                .body("totalElements", greaterThanOrEqualTo(4))
                .body("totalPages", greaterThanOrEqualTo(2))
                .body("first", is(true))
                .body("last", is(false))
                .body("hasPrevious", is(false))
                .body("hasNext", is(true));
    }

    @Test
    void paginatesProducts() {
        api()
                .queryParam("page", 0)
                .queryParam("size", 2)
                .get("/api/products")
                .then()
                .statusCode(200)
                .body("content.size()", is(2))
                .body("totalElements", is(4))
                .body("totalPages", is(2))
                .body("first", is(true))
                .body("last", is(false))
                .body("hasNext", is(true))
                .body("hasPrevious", is(false));
    }

    @Test
    void clampsInvalidPagingParameters() {
        api()
                .queryParam("page", -1)
                .queryParam("size", 1000)
                .get("/api/products")
                .then()
                .statusCode(200)
                .body("page", is(0))
                .body("size", is(100))
                .body("content.size()", is(4));
    }

    @Test
    void returnsProblemDetailsWhenExplicitlyRequested() {
        api()
                .header("Accept", "application/problem+json")
                .get("/api/orders/{id}", 999999L)
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("type", is("about:blank"))
                .body("title", is("Not Found"))
                .body("status", is(404))
                .body("detail", containsString("注文が見つかりません"))
                .body("instance", containsString("/api/orders/999999"));
    }

    @Test
    void keepsDefaultErrorShapeWithoutProblemDetails() {
        api()
                .get("/api/orders/{id}", 999999L)
                .then()
                .statusCode(404)
                .contentType("application/json")
                .body("status", is(404))
                .body("error", is("BUSINESS_ERROR"))
                .body("message", containsString("注文が見つかりません"));
    }

    @Test
    void sortsProductsByStockDescending() {
        api()
                .queryParam("sort", "stock,desc")
                .queryParam("size", 1)
                .get("/api/products")
                .then()
                .statusCode(200)
                .body("content.size()", is(1))
                .body("content[0].code", is("P004"));
    }

    @Test
    void sortsOrdersByIdAscending() {
        api()
                .queryParam("sort", "id,asc")
                .queryParam("size", 1)
                .get("/api/orders")
                .then()
                .statusCode(200)
                .body("content.size()", is(1))
                .body("content[0].id", is(1));
    }

    @Test
    void acceptsMultipleSortCriteria() {
        api()
                .queryParam("sort", "status,asc", "id,desc")
                .get("/api/orders")
                .then()
                .statusCode(200)
                .body("content.size()", greaterThanOrEqualTo(1));
    }

    @Test
    void rejectsUnknownSortField() {
        api()
                .queryParam("sort", "password,asc")
                .get("/api/orders")
                .then()
                .statusCode(400)
                .body("error", is("BUSINESS_ERROR"));
    }

    @Test
    void sortsByInheritedAuditField() {
        api()
                .queryParam("sort", "createdAt,desc")
                .get("/api/orders")
                .then()
                .statusCode(200)
                .body("content.size()", greaterThanOrEqualTo(1));
    }
}
