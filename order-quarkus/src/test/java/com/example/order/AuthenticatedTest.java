package com.example.order;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;

import static io.restassured.RestAssured.given;

abstract class AuthenticatedTest {

    private RequestSpecification auth;

    @BeforeEach
    void authenticate() {
        String token = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"user","password":"user"}
                        """)
                .post("/api/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .path("token");
        auth = given().auth().oauth2(token);
    }

    protected RequestSpecification api() {
        return given(auth);
    }
}
