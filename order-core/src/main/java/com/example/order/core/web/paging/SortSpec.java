package com.example.order.core.web.paging;

import com.example.order.core.web.error.BusinessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whitelist for sortable fields. Only fields in {@link #allowedFields()} are
 * turned into JPQL, so user input never becomes a query fragment.
 */
public record SortSpec(Set<String> allowedFields, List<SortOrder> defaultSort) {

    public SortSpec {
        allowedFields = Set.copyOf(allowedFields);
        defaultSort = List.copyOf(defaultSort);
    }

    public List<SortOrder> parse(List<String> rawSort) {
        if (rawSort == null || rawSort.isEmpty()) {
            return defaultSort;
        }

        List<SortOrder> result = new ArrayList<>();
        for (String raw : rawSort) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String[] parts = raw.split(",", -1);
            if (parts.length > 2) {
                throw BusinessException.badRequest(
                        "ソートは field,asc|desc の形式で指定してください: " + raw);
            }

            String field = parts[0].trim();
            if (!allowedFields.contains(field)) {
                throw BusinessException.badRequest("ソートできない項目です: " + field);
            }

            SortOrder.Direction direction = parts.length == 2 && !parts[1].isBlank()
                    ? parseDirection(parts[1].trim())
                    : SortOrder.Direction.ASC;
            result.add(new SortOrder(field, direction));
        }

        return result.isEmpty() ? defaultSort : List.copyOf(result);
    }

    private SortOrder.Direction parseDirection(String value) {
        try {
            return SortOrder.Direction.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest(
                    "ソート方向は asc または desc で指定してください: " + value);
        }
    }
}
