package com.example.order.core.repository;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class OrderRepository {

    @Inject
    private EntityManager entityManager;

    public Optional<Order> findById(Long id) {
        return Optional.ofNullable(entityManager.find(Order.class, id));
    }

    public List<Order> findAll() {
        return entityManager.createQuery("select o from Order o order by o.id desc", Order.class)
                .getResultList();
    }

    public List<Order> findByStatus(OrderStatus status) {
        return entityManager.createQuery(
                        "select o from Order o where o.status = :status order by o.id desc", Order.class)
                .setParameter("status", status)
                .getResultList();
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
