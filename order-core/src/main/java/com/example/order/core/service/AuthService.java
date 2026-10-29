package com.example.order.core.service;

import com.example.order.core.repository.AuthTokenRepository;
import com.example.order.core.repository.UserRepository;
import com.example.order.core.security.PasswordHasher;
import com.example.order.core.security.TokenService;
import com.example.order.core.web.error.AuthException;
import com.example.order.domain.AuthToken;
import com.example.order.domain.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class AuthService {

    private static final Duration TOKEN_TTL = Duration.ofHours(8);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    /** Throttles the {@code last_used_at} UPDATE to at most once per minute per token. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
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

        String token = tokenService.newToken();
        String csrfToken = tokenService.newCsrfToken();
        LocalDateTime expiresAt = now.plus(TOKEN_TTL);
        authTokenRepository.persist(new AuthToken(
                tokenService.hash(token), user, tokenService.hash(csrfToken), expiresAt));

        return new LoginResult(token, csrfToken, expiresAt, toAuthenticatedUser(user));
    }

    @Transactional
    public Optional<AuthSession> authenticate(String token) {
        LocalDateTime now = LocalDateTime.now();
        return authTokenRepository.findByTokenHash(tokenService.hash(token))
                .filter(authToken -> authToken.getExpiresAt().isAfter(now))
                .filter(authToken -> !authToken.isIdleSince(now, IDLE_TIMEOUT))
                .map(authToken -> {
                    if (authToken.shouldTouch(now, TOUCH_INTERVAL)) {
                        authToken.touch(now);
                    }
                    return new AuthSession(
                            toAuthenticatedUser(authToken.getUser()),
                            authToken.getTokenHash(),
                            authToken.getCsrfTokenHash());
                });
    }

    @Transactional
    public void logout(String token) {
        authTokenRepository.findByTokenHash(tokenService.hash(token))
                .ifPresent(authTokenRepository::delete);
    }

    private AuthenticatedUser toAuthenticatedUser(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), Set.of(user.getRole()));
    }

    public record AuthenticatedUser(Long id, String username, Set<String> roles) {
    }

    public record AuthSession(AuthenticatedUser user, String tokenHash, String csrfTokenHash) {
    }

    public record LoginResult(String token, String csrfToken, LocalDateTime expiresAt,
                              AuthenticatedUser user) {
    }
}
