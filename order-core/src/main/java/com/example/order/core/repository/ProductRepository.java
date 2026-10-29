package com.example.order.core.repository;

import com.example.order.domain.Product;
import com.example.order.core.web.paging.SortOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductRepository {

    @Inject
    private EntityManager entityManager;

    public List<Product> findPage(long offset, int limit, List<SortOrder> sort) {
        String orderBy = sort.isEmpty()
                ? "p.code asc"
                : sort.stream().map(sortOrder -> sortOrder.jpql("p")).collect(Collectors.joining(", "));
        return entityManager.createQuery("select p from Product p order by " + orderBy, Product.class)
                .setFirstResult((int) Math.min(offset, Integer.MAX_VALUE))
                .setMaxResults(limit)
                .getResultList();
    }

    public Optional<Product> findByCode(String code) {
        return entityManager.createQuery("select p from Product p where p.code = :code", Product.class)
                .setParameter("code", code)
                .getResultStream()
                .findFirst();
    }

    public long count() {
        return entityManager.createQuery("select count(p) from Product p", Long.class)
                .getSingleResult();
    }

    public void persist(Product product) {
        entityManager.persist(product);
    }
}
