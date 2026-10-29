package com.example.order.core.repository;

import com.example.order.domain.Invoice;
import com.example.order.core.web.paging.SortOrder;
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

    public List<Invoice> findPage(String billingMonth, long offset, int limit, List<SortOrder> sort) {
        String jpql = (billingMonth == null
                ? "select i from Invoice i"
                : "select i from Invoice i where i.billingMonth = :month")
                + " order by " + Jpql.orderBy(sort, "i", "i.id desc");
        return Jpql.page(entityManager, jpql, Invoice.class, offset, limit, query -> {
            if (billingMonth != null) {
                query.setParameter("month", billingMonth);
            }
        });
    }

    public long count(String billingMonth) {
        String jpql = billingMonth == null
                ? "select count(i) from Invoice i"
                : "select count(i) from Invoice i where i.billingMonth = :month";
        return Jpql.count(entityManager, jpql, query -> {
            if (billingMonth != null) {
                query.setParameter("month", billingMonth);
            }
        });
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
