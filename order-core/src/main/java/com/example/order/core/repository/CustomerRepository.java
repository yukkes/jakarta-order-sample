package com.example.order.core.repository;

import com.example.order.domain.Customer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CustomerRepository {

    @Inject
    private EntityManager entityManager;

    public List<Customer> findAll() {
        return entityManager.createQuery("select c from Customer c order by c.code", Customer.class)
                .getResultList();
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
