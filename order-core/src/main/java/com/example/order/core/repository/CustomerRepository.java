package com.example.order.core.repository;

import com.example.order.domain.Customer;
import com.example.order.core.web.paging.SortOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CustomerRepository {

    @Inject
    private EntityManager entityManager;

    public List<Customer> findPage(long offset, int limit, List<SortOrder> sort) {
        String orderBy = Jpql.orderBy(sort, "c", "c.code asc");
        return Jpql.page(entityManager, "select c from Customer c order by " + orderBy,
                Customer.class, offset, limit);
    }

    public long count() {
        return Jpql.count(entityManager, "select count(c) from Customer c");
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
