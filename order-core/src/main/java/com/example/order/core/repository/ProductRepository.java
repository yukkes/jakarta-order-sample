package com.example.order.core.repository;

import com.example.order.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ProductRepository {

    @Inject
    private EntityManager entityManager;

    public List<Product> findAll() {
        return entityManager.createQuery("select p from Product p order by p.code", Product.class)
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
