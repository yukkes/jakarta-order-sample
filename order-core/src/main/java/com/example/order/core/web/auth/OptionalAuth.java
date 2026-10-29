package com.example.order.core.web.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that authenticates a token when one is sent but also accepts
 * anonymous calls (for example {@code /api/auth/status}). An invalid token is still 401.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface OptionalAuth {
}
