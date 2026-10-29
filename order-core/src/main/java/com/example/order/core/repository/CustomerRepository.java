package com.example.order.core.repository;

import com.example.order.domain.Customer;
import com.example.order.core.web.paging.SortOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class CustomerRepository {

    @Inject
    private EntityManager entityManager;

    public List<Customer> findPage(long offset, int limit, List<SortOrder> sort) {
        String orderBy = sort.isEmpty()
                ? "c.code asc"
                : sort.stream().map(sortOrder -> sortOrder.jpql("c")).collect(Collectors.joining(", "));
        return entityManager.createQuery("select c from Customer c order by " + orderBy, Customer.class)
                .setFirstResult((int) Math.min(offset, Integer.MAX_VALUE))
                .setMaxResults(limit)
                .getResultList();
    }

    public long count() {
        return entityManager.createQuery("select count(c) from Customer c", Long.class)
                .getSingleResult();
    }

    public Optional<Customer> findByCode(String code) {
        return entityManager.createQuery("select c from Customer c where c.code = :code", Customer.class)
                .setParameter("code", code)
                .getResultStream()
                .findFirst();
    }

    public void persist(Customer customer) {
        entityManager.persist(customer);
    }
}
