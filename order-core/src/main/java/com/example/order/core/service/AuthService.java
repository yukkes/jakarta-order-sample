package com.example.order.core.service;

import com.example.order.core.repository.AuthTokenRepository;
import com.example.order.core.repository.UserRepository;
import com.example.order.core.security.PasswordHasher;
import com.example.order.core.security.SecuritySettings;
import com.example.order.core.security.TokenService;
import com.example.order.core.web.error.AuthException;
import com.example.order.domain.AuthToken;
import com.example.order.domain.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

/**
 * Two-token authentication.
 *
 * <ul>
 *   <li><b>Access token</b>: stateless HS256 JWT, verified without touching the database.
 *       Its short lifetime bounds how long a revoked session or disabled user can still
 *       make requests.</li>
 *   <li><b>Refresh token</b>: opaque, random, stored hashed in {@code auth_tokens}. It is
 *       only presented to {@code /api/auth/refresh}, which is the only place that pays a
 *       DB query. Refresh tokens are rotated on every use, are idled out after
 *       {@link #IDLE_TIMEOUT} and are capped at {@link #MAX_SESSIONS_PER_USER} per user.</li>
 * </ul>
 */
@ApplicationScoped
public class AuthService {

    private static final Duration REFRESH_TTL = Duration.ofHours(8);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_SESSIONS_PER_USER = 10;
    private static final int MAX_FAILED_LOGINS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    @Inject
    private UserRepository userRepository;

    @Inject
    private AuthTokenRepository authTokenRepository;

    @Inject
    private PasswordHasher passwordHasher;

    @Inject
    private TokenService tokenService;

    @Inject
    private SecuritySettings settings;

    /**
     * Failed attempts must be committed even though AuthException is thrown, hence
     * dontRollbackOn. Unknown, disabled and locked accounts all get the same 401 after
     * the same hashing work, so none of those states can be told apart from outside.
     */
    @Transactional(dontRollbackOn = AuthException.class)
    public LoginResult login(String username, String password) {
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.findByUsernameForUpdate(username)
                .filter(User::isEnabled)
                .orElse(null);
        if (user == null || user.isLockedAt(now)) {
            passwordHasher.matchesDummy(password);
            throw AuthException.invalidCredentials();
        }

        if (!passwordHasher.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(now, MAX_FAILED_LOGINS, LOCK_DURATION);
            throw AuthException.invalidCredentials();
        }
        user.recordSuccessfulLogin();
        if (passwordHasher.needsRehash(user.getPasswordHash())) {
            user.changePassword(passwordHasher.hash(password));
        }

        authTokenRepository.deleteExpired(now);
        // Keep the number of parallel sessions bounded: drop the least recently used ones.
        authTokenRepository.deleteOldestBeyond(user, now, MAX_SESSIONS_PER_USER - 1);

        String refreshToken = tokenService.newToken();
        AuthToken session = new AuthToken(tokenService.hash(refreshToken), user, now.plus(REFRESH_TTL));
        authTokenRepository.persist(session);

        return issue(user, session, refreshToken, now);
    }

    /**
     * Exchanges a refresh token for a new access token and a rotated refresh token.
     * This is the only request that reads session state from the database.
     */
    @Transactional
    public Optional<LoginResult> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<AuthToken> found = authTokenRepository.findByTokenHash(tokenService.hash(refreshToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AuthToken current = found.get();
        if (!current.getExpiresAt().isAfter(now) || current.isIdleSince(now, IDLE_TIMEOUT)) {
            authTokenRepository.delete(current);
            return Optional.empty();
        }

        // Rotation: a stolen refresh token is only usable once.
        String nextRefreshToken = tokenService.newToken();
        authTokenRepository.delete(current);
        AuthToken next = new AuthToken(tokenService.hash(nextRefreshToken), current.getUser(), now.plus(REFRESH_TTL));
        authTokenRepository.persist(next);

        return Optional.of(issue(current.getUser(), next, nextRefreshToken, now));
    }

    /** Verifies the access token signature and claims. No database access. */
    public Optional<AuthSession> authenticate(String accessToken) {
        Instant now = Instant.now();
        return tokenService.parseAccessToken(settings.tokenSecret(), accessToken)
                .filter(token -> token.expiresAt().isAfter(now))
                .map(token -> new AuthSession(
                        new AuthenticatedUser(token.userId(), token.username(), token.roles()),
                        token.csrfToken(),
                        token.sessionId()));
    }

    /** Revokes the refresh token; the access token expires on its own. */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        authTokenRepository.findByTokenHash(tokenService.hash(refreshToken))
                .ifPresent(authTokenRepository::delete);
    }

    private LoginResult issue(User user, AuthToken session, String refreshToken, LocalDateTime now) {
        Instant issuedAt = toInstant(now);
        Instant expiresAt = issuedAt.plus(settings.accessTokenTtl());
        String csrfToken = tokenService.newCsrfToken();
        String accessToken = tokenService.accessToken(
                settings.tokenSecret(),
                user.getId(),
                user.getUsername(),
                Set.of(user.getRole()),
                session.getId(),
                csrfToken,
                issuedAt,
                expiresAt);

        return new LoginResult(
                accessToken,
                refreshToken,
                csrfToken,
                LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()),
                LocalDateTime.ofInstant(toInstant(now.plus(REFRESH_TTL)), ZoneId.systemDefault()),
                toAuthenticatedUser(user));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private AuthenticatedUser toAuthenticatedUser(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), Set.of(user.getRole()));
    }

    public record AuthenticatedUser(Long id, String username, Set<String> roles) {
    }

    public record AuthSession(AuthenticatedUser user, String csrfToken, long sessionId) {
    }

    public record LoginResult(String accessToken, String refreshToken, String csrfToken,
                              LocalDateTime expiresAt, LocalDateTime refreshExpiresAt,
                              AuthenticatedUser user) {
    }
}
