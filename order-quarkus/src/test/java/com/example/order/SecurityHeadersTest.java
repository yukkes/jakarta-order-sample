package com.example.order;

import com.example.order.core.web.SecurityHeaders;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The security headers are added by the runtime (Quarkus: {@code quarkus.http.header.*},
 * Servlet runtimes: {@code SecurityHeadersFilter}), so this test also guards the UI rule
 * that keeps the CSP usable without {@code 'unsafe-inline'}.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class SecurityHeadersTest extends AuthenticatedTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/login.html"})
    void staticPagesCarrySecurityHeaders(String path) {
        given()
                .get(path)
                .then()
                .statusCode(200)
                .header("Content-Security-Policy", allOf(
                        containsString("default-src 'self'"),
                        containsString("script-src 'self'"),
                        containsString("style-src 'self'"),
                        containsString("frame-ancestors 'none'"),
                        not(containsString("unsafe-inline"))))
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer");
    }

    @Test
    void apiResponsesCarrySecurityHeaders() {
        api()
                .get("/api/products?page=0&size=1")
                .then()
                .statusCode(200)
                .header("Content-Security-Policy", containsString("default-src 'self'"))
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer");
    }

    /**
     * Quarkus sets the headers from {@code application.properties}, which cannot reference
     * the constants. This keeps the two in sync: changing one without the other fails here.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/login.html"})
    void quarkusHeaderConfigMatchesTheCoreValuesForPages(String path) {
        assertHeadersMatchTheCore(given().get(path).then().statusCode(200).extract());
    }

    @Test
    void quarkusHeaderConfigMatchesTheCoreValuesForApiResponses() {
        assertHeadersMatchTheCore(api().get("/api/products?page=0&size=1").then().statusCode(200).extract());
    }

    private static void assertHeadersMatchTheCore(ExtractableResponse<Response> response) {
        SecurityHeaders.responseHeaders()
                .forEach((name, value) -> assertEquals(value, response.header(name), name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/login.html"})
    void pagesDoNotUseInlineScriptOrStyle(String path) {
        String html = given()
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertThat(html, not(containsString("onclick=")));
        assertThat(html, not(containsString("<style")));
        assertThat(html, not(containsString("style=\"")));
        assertThat(html, not(containsString("javascript:")));
    }
}
