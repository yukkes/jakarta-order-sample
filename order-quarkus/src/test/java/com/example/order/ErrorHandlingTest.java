package com.example.order;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ErrorHandlingTest {

    private String token;

    @BeforeEach
    void login() {
        token = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"user","password":"user"}
                        """)
                .post("/api/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .path("token");
    }

    @Test
    void unexpectedExceptionBecomesGeneric500() {
        String body = given()
                .auth().oauth2(token)
                .get("/api/test/fail")
                .then()
                .statusCode(500)
                .contentType(ContentType.JSON)
                .body("error", is("INTERNAL_ERROR"))
                .body("message", containsString("errorId="))
                .extract()
                .asString();

        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("secret internal detail")));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("IllegalStateException")));
    }

    @Test
    void unexpectedExceptionAsProblemDetail() {
        given()
                .auth().oauth2(token)
                .accept("application/problem+json")
                .get("/api/test/fail")
                .then()
                .statusCode(500)
                .contentType("application/problem+json")
                .body("status", is(500))
                .body("detail", containsString("errorId="));
    }

    @Test
    void unknownPathIsJson404() {
        given()
                .auth().oauth2(token)
                .get("/api/does-not-exist")
                .then()
                .statusCode(404)
                .contentType(ContentType.JSON)
                .body("error", is("NOT_FOUND"))
                .body("message", not(containsString("does-not-exist")));
    }

    @Test
    void wrongMethodKeeps405() {
        given()
                .auth().oauth2(token)
                .delete("/api/products")
                .then()
                .statusCode(405)
                .body("error", is("METHOD_NOT_ALLOWED"));
    }

    @Test
    void malformedJsonIs400Not500() {
        given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body("{not json")
                .post("/api/orders")
                .then()
                .statusCode(400);
    }
}
