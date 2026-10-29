package com.example.order.core.web.paging;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageQueryTest {

    private static final SortSpec SPEC = new SortSpec(Set.of("id"), List.of());

    @Test
    void clampsPageAndSizeInsteadOfRejecting() {
        assertEquals(0, new PageQuery(-1, 20).page());
        assertEquals(1, new PageQuery(0, 0).size());
        assertEquals(PageQuery.MAX_SIZE, new PageQuery(0, 1_000).size());
        assertEquals(1, new PageQuery(0, -5).size());
    }

    @Test
    void computesOffsetWithoutOverflowingForHugePages() {
        // page * size is computed in a long, so this must not wrap around.
        assertEquals(0L, new PageQuery(0, PageQuery.MAX_SIZE).offset());
        long offset = new PageQuery(Integer.MAX_VALUE, PageQuery.MAX_SIZE).offset();
        assertTrue(offset > 0);
        assertEquals((long) Integer.MAX_VALUE * PageQuery.MAX_SIZE, offset);
    }

    @Test
    void treatsMissingSortAsAnEmptyList() {
        assertEquals(List.of(), new PageQuery(0, 20).sort());
        assertEquals(List.of(), new PageQuery(0, 20, null).sort());
    }

    @Test
    void parsesTheSortThroughTheWhitelist() {
        PageQuery query = PageQuery.of(2, 5, List.of("id,desc"), SPEC);

        assertEquals(2, query.page());
        assertEquals(5, query.size());
        assertEquals(List.of(new SortOrder("id", SortOrder.Direction.DESC)), query.sort());
        assertEquals(10L, query.offset());
    }
}
