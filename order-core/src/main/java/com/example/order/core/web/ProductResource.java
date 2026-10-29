package com.example.order.core.web;

import com.example.order.core.web.auth.RequireRole;
import com.example.order.core.service.CatalogService;
import com.example.order.api.model.ProductResponse;
import com.example.order.core.web.paging.PageQuery;
import com.example.order.core.web.paging.PageResponse;
import com.example.order.core.web.paging.SortOrder;
import com.example.order.core.web.paging.SortSpec;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Set;

@RequireRole({"USER", "ADMIN"})
@Path("/products")
@Produces({MediaType.APPLICATION_JSON, "application/problem+json"})
public class ProductResource {

    private static final SortSpec SORT = new SortSpec(
            Set.of("code", "name", "unitPrice", "stock"),
            List.of(new SortOrder("code", SortOrder.Direction.ASC)));

    @Inject
    private CatalogService catalogService;

    @GET
    public PageResponse<ProductResponse> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") List<String> sort) {
        return catalogService.listProducts(PageQuery.of(page, size, sort, SORT));
    }
}
