package com.example.order.core.web.paging;

import java.util.List;

/**
 * JSON shape for paginated list endpoints. Kept as a record so it can be used
 * with both JSON-B and Jackson without a generated DTO.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext,
        boolean hasPrevious) {

    public static <T> PageResponse<T> of(List<T> content, PageQuery query, long totalElements) {
        int totalPages = totalElements == 0
                ? 0
                : (int) Math.min(Integer.MAX_VALUE, (totalElements + query.size() - 1) / query.size());
        boolean first = query.page() == 0;
        boolean last = totalPages == 0 || query.page() >= totalPages - 1;
        return new PageResponse<>(
                content,
                query.page(),
                query.size(),
                totalElements,
                totalPages,
                first,
                last,
                !last,
                !first);
    }
}
