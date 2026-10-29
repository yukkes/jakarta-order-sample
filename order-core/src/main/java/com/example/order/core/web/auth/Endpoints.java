package com.example.order.core.web.auth;

import jakarta.ws.rs.container.ResourceInfo;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/** Looks up endpoint security annotations on the matched resource method, then its class. */
final class Endpoints {

    private Endpoints() {
    }

    static <A extends Annotation> A find(ResourceInfo resourceInfo, Class<A> type) {
        Method method = resourceInfo.getResourceMethod();
        if (method == null) {
            return null;
        }
        A annotation = method.getAnnotation(type);
        return annotation != null ? annotation : resourceInfo.getResourceClass().getAnnotation(type);
    }

    static boolean has(ResourceInfo resourceInfo, Class<? extends Annotation> type) {
        return find(resourceInfo, type) != null;
    }
}
