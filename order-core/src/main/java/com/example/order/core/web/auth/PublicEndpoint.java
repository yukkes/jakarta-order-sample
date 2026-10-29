package com.example.order.core.web.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JAX-RS resource method (or class) that anyone may call without a token.
 * Every other endpoint needs {@link RequireRole} or {@link OptionalAuth}; endpoints
 * with none of the three are rejected with 403 by {@link RoleAuthorizationFilter}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PublicEndpoint {
}
