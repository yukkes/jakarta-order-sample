package com.example.order.core.web.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Portable role metadata checked by {@link RoleAuthorizationFilter}.
 *
 * <p>Do not use {@code @RolesAllowed} on JAX-RS resources here: some containers
 * (notably GlassFish) then enable container-managed authentication and reject the
 * application's own login endpoint before the JAX-RS filter runs.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequireRole {
    String[] value();
}
