package com.example.order.core.web.paging;

import java.util.Locale;

public record SortOrder(String field, Direction direction) {

    public enum Direction {
        ASC,
        DESC
    }

    public String jpql(String alias) {
        return alias + "." + field + " " + direction.name().toLowerCase(Locale.ROOT);
    }
}
