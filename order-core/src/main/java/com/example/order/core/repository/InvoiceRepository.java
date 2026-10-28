package com.example.order.core.repository;

import com.example.order.domain.Invoice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class InvoiceRepository {

    @Inject
    private EntityManager entityManager;

    public Optional<Invoice> findById(Long id) {
        return Optional.ofNullable(entityManager.find(Invoice.class, id));
    }

    public List<Invoice> findAll() {
        return entityManager.createQuery("select i from Invoice i order by i.id desc", Invoice.class)
                .getResultList();
    }

    public List<Invoice> findByMonth(String billingMonth) {
        return entityManager.createQuery(
                        "select i from Invoice i where i.billingMonth = :month order by i.id desc", Invoice.class)
                .setParameter("month", billingMonth)
                .getResultList();
    }

    public boolean existsByInvoiceNo(String invoiceNo) {
        Long count = entityManager.createQuery(
                        "select count(i) from Invoice i where i.invoiceNo = :invoiceNo", Long.class)
                .setParameter("invoiceNo", invoiceNo)
                .getSingleResult();
        return count > 0;
    }

    public long countByMonth(String billingMonth) {
        return entityManager.createQuery(
                        "select count(i) from Invoice i where i.billingMonth = :month", Long.class)
                .setParameter("month", billingMonth)
                .getSingleResult();
    }

    public void persist(Invoice invoice) {
        entityManager.persist(invoice);
    }
}
