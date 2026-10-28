package com.example.order;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class OpenApiTest {

    @Test
    void exposesOpenApiDocument() {
        given()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body(containsString("/api/orders"));
    }
}
