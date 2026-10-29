package com.example.order;

import com.example.order.core.repository.UserRepository;
import com.example.order.core.security.PasswordHasher;
import com.example.order.domain.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class AuthTest {

    private static final String ORDER_BODY = """
            {"customerCode":"C001","lines":[{"productCode":"P002","quantity":1}]}
            """;

    @Inject
    UserRepository userRepository;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    EntityManager entityManager;

    @Test
    void rejectsAnonymousRequests() {
        given()
                .get("/api/orders")
                .then()
                .statusCode(401)
                .body("error", is("UNAUTHORIZED"));
    }

    @Test
    void statusIsPublicAndReflectsLogin() {
        given()
                .get("/api/auth/status")
                .then()
                .statusCode(200)
                .body("authenticated", is(false));

        given()
                .auth().oauth2(login("user", "user"))
                .get("/api/auth/status")
                .then()
                .statusCode(200)
                .body("authenticated", is(true))
                .body("username", is("user"));
    }

    @Test
    void rejectsInvalidPassword() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"user","password":"wrong"}
                        """)
                .post("/api/auth/login")
                .then()
                .statusCode(401)
                .body("error", is("AUTH_ERROR"));
    }

    @Test
    void bearerTokenEnforcesRoles() {
        String userToken = login("user", "user");
        given()
                .auth().oauth2(userToken)
                .get("/api/orders")
                .then()
                .statusCode(200);

        given()
                .auth().oauth2(userToken)
                .get("/api/batch/executions")
                .then()
                .statusCode(403)
                .body("error", is("FORBIDDEN"));

        String adminToken = login("admin", "admin");
        given()
                .auth().oauth2(adminToken)
                .get("/api/batch/executions")
                .then()
                .statusCode(200);
    }

    @Test
    void sessionCookieRequiresCsrfForStateChanges() {
        Response session = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"user","password":"user"}
                        """)
                .post("/api/auth/session")
                .then()
                .statusCode(200)
                .body("username", is("user"))
                .extract()
                .response();

        String sessionCookie = session.getCookie("ORDER_SESSION");
        String csrfCookie = session.getCookie("XSRF-TOKEN");
        assertThat(sessionCookie, notNullValue());
        assertThat(csrfCookie, notNullValue());

        given()
                .cookie("ORDER_SESSION", sessionCookie)
                .get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("username", is("user"));

        given()
                .cookie("ORDER_SESSION", sessionCookie)
                .cookie("XSRF-TOKEN", csrfCookie)
                .contentType(ContentType.JSON)
                .body(ORDER_BODY)
                .post("/api/orders")
                .then()
                .statusCode(403)
                .body("error", is("FORBIDDEN"));

        given()
                .cookie("ORDER_SESSION", sessionCookie)
                .cookie("XSRF-TOKEN", csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie)
                .contentType(ContentType.JSON)
                .body(ORDER_BODY)
                .post("/api/orders")
                .then()
                .statusCode(201);
    }

    @Test
    void logoutRevokesTheRefreshTokenButNotTheAccessToken() {
        Response session = sessionLogin("user", "user");
        String accessToken = session.getCookie("ORDER_SESSION");
        String csrfToken = session.getCookie("XSRF-TOKEN");
        String refreshToken = session.getCookie("ORDER_REFRESH");

        given()
                .cookie("ORDER_SESSION", accessToken)
                .cookie("XSRF-TOKEN", csrfToken)
                .cookie("ORDER_REFRESH", refreshToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .post("/api/auth/logout")
                .then()
                .statusCode(204);

        // Access tokens are stateless: they stay valid until they expire (short TTL).
        given().auth().oauth2(accessToken).get("/api/orders").then().statusCode(200);
        // Revocation happens at the refresh token, which is rotated on every use.
        given().cookie("ORDER_REFRESH", refreshToken).post("/api/auth/refresh").then().statusCode(401);
    }

    @Test
    void disablingUserStopsRefreshButNotTheAccessToken() {
        createUser("disable-me", passwordHasher.hash("disable-me"));
        Response session = sessionLogin("disable-me", "disable-me");
        String accessToken = session.getCookie("ORDER_SESSION");
        String refreshToken = session.getCookie("ORDER_REFRESH");
        given().auth().oauth2(accessToken).get("/api/orders").then().statusCode(200);

        QuarkusTransaction.requiringNew().run(() ->
                userRepository.findByUsername("disable-me").orElseThrow().setEnabled(false));

        // Bounded by the access token TTL; the refresh path is rejected immediately.
        given().auth().oauth2(accessToken).get("/api/orders").then().statusCode(200);
        given().cookie("ORDER_REFRESH", refreshToken).post("/api/auth/refresh").then().statusCode(401);
    }

    @Test
    void upgradesWeakHashOnLogin() {
        String weak = PasswordHashes.pbkdf2("rehash-me", 210_000);
        createUser("rehash-me", weak);
        assertThat(passwordHasher.needsRehash(weak), is(true));

        login("rehash-me", "rehash-me");

        String upgraded = QuarkusTransaction.requiringNew().call(() ->
                userRepository.findByUsername("rehash-me").orElseThrow().getPasswordHash());
        assertThat(upgraded, startsWith("$pbkdf2-sha256$i=600000$"));
        assertThat(passwordHasher.needsRehash(upgraded), is(false));
        login("rehash-me", "rehash-me");
    }

    @Test
    void unknownUserGetsSameErrorAsWrongPassword() {
        String wrongPassword = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"user","password":"wrong"}
                        """)
                .post("/api/auth/login")
                .then()
                .statusCode(401)
                .extract()
                .path("message");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"no-such-user","password":"wrong"}
                        """)
                .post("/api/auth/login")
                .then()
                .statusCode(401)
                .body("message", is(wrongPassword));
    }

    @Test
    void locksAccountAfterFiveFailedLogins() {
        createUser("lock-me", passwordHasher.hash("lock-me"));
        for (int attempt = 0; attempt < 5; attempt++) {
            loginStatus("lock-me", "wrong", 401);
        }

        // Even the right password is rejected, with the same message as a wrong one.
        String locked = loginStatus("lock-me", "lock-me", 401);
        assertThat(locked, is(loginStatus("no-such-user", "wrong", 401)));

        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createQuery("update User u set u.lockedUntil = :past where u.username = 'lock-me'")
                .setParameter("past", java.time.LocalDateTime.now().minusMinutes(1))
                .executeUpdate());
        login("lock-me", "lock-me");
    }

    @Test
    void successfulLoginResetsFailureCount() {
        createUser("reset-me", passwordHasher.hash("reset-me"));
        for (int attempt = 0; attempt < 4; attempt++) {
            loginStatus("reset-me", "wrong", 401);
        }
        login("reset-me", "reset-me");

        int failures = QuarkusTransaction.requiringNew().call(() ->
                userRepository.findByUsername("reset-me").orElseThrow().getFailedLoginCount());
        assertThat(failures, is(0));
        // Four more failures must not lock the account, because the count restarted.
        for (int attempt = 0; attempt < 4; attempt++) {
            loginStatus("reset-me", "wrong", 401);
        }
        login("reset-me", "reset-me");
    }

    @Test
    void endpointWithoutSecurityAnnotationIsDenied() {
        given()
                .get("/api/test/unannotated")
                .then()
                .statusCode(401);

        given()
                .auth().oauth2(login("admin", "admin"))
                .get("/api/test/unannotated")
                .then()
                .statusCode(403)
                .body("error", is("FORBIDDEN"));
    }

    @Test
    void automaticOptionsResponseStaysAnonymous() {
        int status = given().options("/api/orders").then().extract().statusCode();
        assertThat(status, org.hamcrest.Matchers.lessThan(300));
    }

    @Test
    void meAndLogoutNeedAuthentication() {
        given().get("/api/auth/me").then().statusCode(401);
        given().post("/api/auth/logout").then().statusCode(401);
    }

    @Test
    void sessionLoginIsPublic() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"user","password":"user"}
                        """)
                .post("/api/auth/session")
                .then()
                .statusCode(200)
                .cookie("ORDER_SESSION", notNullValue());
    }

    @Test
    void untrustedForwardedProtoDoesNotChangeCookies() {
        boolean secured = given()
                .header("X-Forwarded-Proto", "https")
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"user","password":"user"}
                        """)
                .post("/api/auth/session")
                .then()
                .statusCode(200)
                .extract()
                .detailedCookie("ORDER_SESSION")
                .isSecured();
        assertThat(secured, is(false));
    }

    private String loginStatus(String username, String password, int status) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password))
                .post("/api/auth/login")
                .then()
                .statusCode(status)
                .extract()
                .path("message");
    }

    @Test
    void idleRefreshTokenExpiresAfterThirtyMinutes() {
        Response session = sessionLogin("user", "user");
        String accessToken = session.getCookie("ORDER_SESSION");
        String refreshToken = session.getCookie("ORDER_REFRESH");
        given().auth().oauth2(accessToken).get("/api/orders").then().statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createQuery("update AuthToken t set t.lastUsedAt = :stale where t.user.username = 'user'")
                .setParameter("stale", java.time.LocalDateTime.now().minusMinutes(31))
                .executeUpdate());

        given().auth().oauth2(accessToken).get("/api/orders").then().statusCode(200);
        given().cookie("ORDER_REFRESH", refreshToken).post("/api/auth/refresh").then().statusCode(401);
    }

    @Test
    void keepsAtMostTenSessionsPerUser() {
        createUser("many-sessions", passwordHasher.hash("many-sessions"));

        Response first = sessionLogin("many-sessions", "many-sessions");
        String firstRefresh = first.getCookie("ORDER_REFRESH");
        for (int session = 1; session < 10; session++) {
            sessionLogin("many-sessions", "many-sessions");
        }

        Response eleventh = sessionLogin("many-sessions", "many-sessions");
        String eleventhRefresh = eleventh.getCookie("ORDER_REFRESH");

        // The oldest refresh token was dropped when the eleventh session started.
        given().cookie("ORDER_REFRESH", firstRefresh).post("/api/auth/refresh").then().statusCode(401);
        given().cookie("ORDER_REFRESH", eleventhRefresh).post("/api/auth/refresh").then().statusCode(200);
    }

    @Test
    void refreshRotatesTheRefreshToken() {
        Response session = sessionLogin("user", "user");
        String firstRefresh = session.getCookie("ORDER_REFRESH");

        Response refreshed = given()
                .cookie("ORDER_REFRESH", firstRefresh)
                .post("/api/auth/refresh")
                .then()
                .statusCode(200)
                .body("username", is("user"))
                .extract()
                .response();

        String secondRefresh = refreshed.getCookie("ORDER_REFRESH");
        String accessToken = refreshed.getCookie("ORDER_SESSION");
        assertThat(secondRefresh, notNullValue());
        assertThat(secondRefresh, is(not(firstRefresh)));
        given().auth().oauth2(accessToken).get("/api/orders").then().statusCode(200);

        // A replayed refresh token is rejected.
        given().cookie("ORDER_REFRESH", firstRefresh).post("/api/auth/refresh").then().statusCode(401);
    }

    @Test
    void refreshNeedsARefreshToken() {
        given().post("/api/auth/refresh").then().statusCode(401);
    }

    @Test
    void rejectsTamperedAccessToken() {
        String token = login("user", "user");
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;

        given().auth().oauth2(tampered).get("/api/orders").then().statusCode(401);
    }

    private void createUser(String username, String passwordHash) {
        QuarkusTransaction.requiringNew().run(() ->
                userRepository.persist(new User(username, passwordHash, "USER")));
    }

    private Response sessionLogin(String username, String password) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password))
                .post("/api/auth/session")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }

    private String login(String username, String password) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password))
                .post("/api/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .path("token");
    }
}
