package com.example.order.core.repository;

import com.example.order.domain.Invoice;
import com.example.order.core.web.paging.SortOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class InvoiceRepository {

    @Inject
    private EntityManager entityManager;

    public Optional<Invoice> findById(Long id) {
        return Optional.ofNullable(entityManager.find(Invoice.class, id));
    }

    public List<Invoice> findPage(String billingMonth, long offset, int limit, List<SortOrder> sort) {
        String orderBy = sort.isEmpty()
                ? "i.id desc"
                : sort.stream().map(sortOrder -> sortOrder.jpql("i")).collect(Collectors.joining(", "));
        String jpql = (billingMonth == null
                ? "select i from Invoice i"
                : "select i from Invoice i where i.billingMonth = :month")
                + " order by " + orderBy;
        var query = entityManager.createQuery(jpql, Invoice.class)
                .setFirstResult((int) Math.min(offset, Integer.MAX_VALUE))
                .setMaxResults(limit);
        if (billingMonth != null) {
            query.setParameter("month", billingMonth);
        }
        return query.getResultList();
    }

    public long count(String billingMonth) {
        String jpql = billingMonth == null
                ? "select count(i) from Invoice i"
                : "select count(i) from Invoice i where i.billingMonth = :month";
        var query = entityManager.createQuery(jpql, Long.class);
        if (billingMonth != null) {
            query.setParameter("month", billingMonth);
        }
        return query.getSingleResult();
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
