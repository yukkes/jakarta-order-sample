package com.example.order.core.web.paging;

import com.example.order.core.web.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whitelist is the only thing that keeps user input out of JPQL, so it is
 * tested without a container.
 */
class SortSpecTest {

    private static final SortSpec SPEC = new SortSpec(
            Set.of("id", "total", "createdAt"),
            List.of(new SortOrder("id", SortOrder.Direction.DESC)));

    @Test
    void fallsBackToTheDefaultSort() {
        assertEquals(List.of(new SortOrder("id", SortOrder.Direction.DESC)), SPEC.parse(null));
        assertEquals(List.of(new SortOrder("id", SortOrder.Direction.DESC)), SPEC.parse(List.of()));
        assertEquals(List.of(new SortOrder("id", SortOrder.Direction.DESC)), SPEC.parse(List.of(" ", "")));
    }

    @Test
    void defaultsToAscendingWhenOnlyTheFieldIsGiven() {
        assertEquals(List.of(new SortOrder("total", SortOrder.Direction.ASC)), SPEC.parse(List.of("total")));
        assertEquals(List.of(new SortOrder("total", SortOrder.Direction.ASC)), SPEC.parse(List.of("total,")));
    }

    @Test
    void acceptsAscendingAndDescendingInAnyCase() {
        assertEquals(List.of(new SortOrder("total", SortOrder.Direction.DESC)), SPEC.parse(List.of("total,desc")));
        assertEquals(List.of(new SortOrder("total", SortOrder.Direction.DESC)), SPEC.parse(List.of("total,DESC")));
        assertEquals(List.of(new SortOrder("total", SortOrder.Direction.ASC)), SPEC.parse(List.of("total, Asc ")));
    }

    @Test
    void keepsTheRequestedOrderForRepeatedParameters() {
        assertEquals(
                List.of(new SortOrder("total", SortOrder.Direction.DESC),
                        new SortOrder("createdAt", SortOrder.Direction.ASC)),
                SPEC.parse(List.of("total,desc", "createdAt,asc")));
    }

    @Test
    void rejectsFieldsOutsideTheWhitelist() {
        BusinessException exception = assertThrows(BusinessException.class, () -> SPEC.parse(List.of("id;DROP,--")));
        assertEquals(400, exception.getStatus());
        assertTrue(exception.getMessage().contains("ソートできない項目"));

        // The record getter of an entity is not in the whitelist either.
        assertThrows(BusinessException.class, () -> SPEC.parse(List.of("customer.passwordHash")));
    }

    @Test
    void rejectsMalformedDirections() {
        assertEquals(400, assertThrows(BusinessException.class, () -> SPEC.parse(List.of("id,up"))).getStatus());
        // Three components mean the caller is trying to smuggle something in.
        assertEquals(400, assertThrows(BusinessException.class, () -> SPEC.parse(List.of("id,asc,1"))).getStatus());
    }

    @Test
    void buildsJpqlOnlyFromWhitelistedValues() {
        assertEquals("o.total desc", new SortOrder("total", SortOrder.Direction.DESC).jpql("o"));
        assertEquals("o.total asc", new SortOrder("total", SortOrder.Direction.ASC).jpql("o"));
    }
}
