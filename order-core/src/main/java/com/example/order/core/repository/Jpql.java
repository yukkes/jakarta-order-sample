package com.example.order.core.repository;

import com.example.order.core.web.paging.SortOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Shared JPQL helpers for the list queries. Every repository used to repeat the
 * same order-by rendering, {@code setFirstResult}/{@code setMaxResults} pair and
 * count query; keeping that here leaves each repository with just its query and
 * filters.
 */
final class Jpql {

    private Jpql() {
    }

    /** Renders the whitelisted sort, falling back to the endpoint's default order. */
    static String orderBy(List<SortOrder> sort, String alias, String defaultOrderBy) {
        if (sort.isEmpty()) {
            return defaultOrderBy;
        }
        return sort.stream()
                .map(sortOrder -> sortOrder.jpql(alias))
                .collect(Collectors.joining(", "));
    }

    static <T> List<T> page(EntityManager entityManager, String jpql, Class<T> type,
                            long offset, int limit) {
        return page(entityManager, jpql, type, offset, limit, query -> {
        });
    }

    static <T> List<T> page(EntityManager entityManager, String jpql, Class<T> type,
                            long offset, int limit, Consumer<TypedQuery<T>> binder) {
        TypedQuery<T> query = entityManager.createQuery(jpql, type)
                .setFirstResult((int) Math.min(offset, Integer.MAX_VALUE))
                .setMaxResults(limit);
        binder.accept(query);
        return query.getResultList();
    }

    static long count(EntityManager entityManager, String jpql) {
        return count(entityManager, jpql, query -> {
        });
    }

    static long count(EntityManager entityManager, String jpql, Consumer<TypedQuery<Long>> binder) {
        TypedQuery<Long> query = entityManager.createQuery(jpql, Long.class);
        binder.accept(query);
        return query.getSingleResult();
    }
}
