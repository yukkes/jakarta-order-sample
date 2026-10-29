package com.example.order.core.web.paging;

import java.util.List;

/**
 * Normalized pagination and sorting request. Invalid page/size values are
 * clamped instead of rejected so that a bad query parameter cannot trigger a
 * 500.
 */
public record PageQuery(int page, int size, List<SortOrder> sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), MAX_SIZE);
        sort = sort == null ? List.of() : List.copyOf(sort);
    }

    public PageQuery(int page, int size) {
        this(page, size, List.of());
    }

    public static PageQuery of(int page, int size, List<String> rawSort, SortSpec spec) {
        return new PageQuery(page, size, spec.parse(rawSort));
    }

    public long offset() {
        return (long) page * size;
    }
}
