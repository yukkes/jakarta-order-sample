package com.example.order.core.service;

import com.example.order.core.repository.CustomerRepository;
import com.example.order.core.repository.OrderRepository;
import com.example.order.core.repository.ProductRepository;
import com.example.order.domain.Customer;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

@ApplicationScoped
public class SeedService {

    @Inject
    private ProductRepository productRepository;

    @Inject
    private CustomerRepository customerRepository;

    @Inject
    private OrderRepository orderRepository;

    @Transactional
    public void seed() {
        if (productRepository.count() > 0) {
            return;
        }

        Customer yamada = new Customer("C001", "山田商事株式会社", "sales@yamada.example");
        Customer sato = new Customer("C002", "佐藤工業株式会社", "buy@sato.example");
        Customer suzuki = new Customer("C003", "鈴木商店", "info@suzuki.example");
        customerRepository.persist(yamada);
        customerRepository.persist(sato);
        customerRepository.persist(suzuki);

        Product laptop = new Product("P001", "ノートPC", new BigDecimal("120000.00"), 20);
        Product mouse = new Product("P002", "ワイヤレスマウス", new BigDecimal("3000.00"), 100);
        Product monitor = new Product("P003", "27インチモニター", new BigDecimal("45000.00"), 10);
        Product cable = new Product("P004", "USB-Cケーブル", new BigDecimal("800.00"), 500);
        productRepository.persist(laptop);
        productRepository.persist(mouse);
        productRepository.persist(monitor);
        productRepository.persist(cable);

        LocalDate lastMonth = YearMonth.now().minusMonths(1).atDay(10);

        Order order1 = new Order("ORD-SEED-001", yamada, lastMonth);
        order1.addLine(laptop, 1);
        order1.addLine(mouse, 2);
        order1.setStatus(OrderStatus.CONFIRMED);
        orderRepository.persist(order1);

        Order order2 = new Order("ORD-SEED-002", sato, lastMonth.plusDays(2));
        order2.addLine(mouse, 5);
        order2.setStatus(OrderStatus.CONFIRMED);
        orderRepository.persist(order2);

        Order order3 = new Order("ORD-SEED-003", suzuki, lastMonth.plusDays(5));
        order3.addLine(monitor, 2);
        order3.setStatus(OrderStatus.CONFIRMED);
        orderRepository.persist(order3);

        Order draft = new Order("ORD-SEED-004", yamada, LocalDate.now());
        draft.addLine(cable, 3);
        orderRepository.persist(draft);
    }
}
