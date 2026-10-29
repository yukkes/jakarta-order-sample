package com.example.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "auth_tokens", indexes = {
        @Index(name = "idx_auth_tokens_expires", columnList = "expires_at")
})
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected AuthToken() {
    }

    public AuthToken(String tokenHash, User user, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.user = user;
        this.expiresAt = expiresAt;
        this.lastUsedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public User getUser() {
        return user;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    /** True when the session has not been used for longer than {@code timeout}. */
    public boolean isIdleSince(LocalDateTime now, Duration timeout) {
        return lastUsedAt.plus(timeout).isBefore(now);
    }

    /** True when {@code lastUsedAt} is old enough to be worth another UPDATE. */
    public boolean shouldTouch(LocalDateTime now, Duration interval) {
        return lastUsedAt.plus(interval).isBefore(now);
    }

    public void touch(LocalDateTime now) {
        this.lastUsedAt = now;
    }
}
