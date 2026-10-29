package com.example.order.core.repository;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.core.web.paging.SortOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class OrderRepository {

    @Inject
    private EntityManager entityManager;

    public Optional<Order> findById(Long id) {
        return Optional.ofNullable(entityManager.find(Order.class, id));
    }

    public List<Order> findPage(OrderStatus status, long offset, int limit, List<SortOrder> sort) {
        String orderBy = sort.isEmpty()
                ? "o.id desc"
                : sort.stream().map(sortOrder -> sortOrder.jpql("o")).collect(Collectors.joining(", "));
        String jpql = (status == null
                ? "select o from Order o"
                : "select o from Order o where o.status = :status")
                + " order by " + orderBy;
        var query = entityManager.createQuery(jpql, Order.class)
                .setFirstResult((int) Math.min(offset, Integer.MAX_VALUE))
                .setMaxResults(limit);
        if (status != null) {
            query.setParameter("status", status);
        }
        return query.getResultList();
    }

    public long count(OrderStatus status) {
        String jpql = status == null
                ? "select count(o) from Order o"
                : "select count(o) from Order o where o.status = :status";
        var query = entityManager.createQuery(jpql, Long.class);
        if (status != null) {
            query.setParameter("status", status);
        }
        return query.getSingleResult();
    }

    /**
     * Stable ordering used by the invoice batch. The query intentionally does
     * not filter by status so that checkpoints remain valid when a chunk changes
     * an order from CONFIRMED to INVOICED.
     */
    public List<Order> findForMonth(YearMonth month, int offset, int limit) {
        return entityManager.createQuery("""
                        select o from Order o
                        where o.orderDate >= :from and o.orderDate < :to
                        order by o.id
                        """, Order.class)
                .setParameter("from", month.atDay(1))
                .setParameter("to", month.plusMonths(1).atDay(1))
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public void persist(Order order) {
        entityManager.persist(order);
    }

    public void flush() {
        entityManager.flush();
    }
}
