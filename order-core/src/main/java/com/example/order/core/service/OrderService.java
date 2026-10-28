package com.example.order.core.service;

import com.example.order.core.repository.CustomerRepository;
import com.example.order.core.repository.OrderRepository;
import com.example.order.core.repository.ProductRepository;
import com.example.order.api.model.CreateOrderLine;
import com.example.order.api.model.CreateOrderRequest;
import com.example.order.api.model.OrderLineResponse;
import com.example.order.api.model.OrderResponse;
import com.example.order.core.web.error.BusinessException;
import com.example.order.domain.Customer;
import com.example.order.domain.Order;
import com.example.order.domain.OrderLine;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class OrderService {

    @Inject
    private OrderRepository orderRepository;

    @Inject
    private CustomerRepository customerRepository;

    @Inject
    private ProductRepository productRepository;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        Customer customer = findCustomer(request.getCustomerCode());
        Order order = new Order(newOrderNo(), customer,
                request.getOrderDate() != null ? request.getOrderDate() : LocalDate.now());

        for (CreateOrderLine line : request.getLines()) {
            Product product = findProduct(line.getProductCode());
            if (line.getQuantity() < 1) {
                throw BusinessException.badRequest("数量は1以上で指定してください: " + line.getProductCode());
            }
            order.addLine(product, line.getQuantity());
        }

        orderRepository.persist(order);
        orderRepository.flush();
        return toResponse(order);
    }

    @Transactional
    public OrderResponse confirmOrder(Long id) {
        Order order = findOrder(id);
        if (order.getStatus() != OrderStatus.DRAFT) {
            throw BusinessException.conflict("DRAFT の注文だけ確定できます: " + order.getOrderNo());
        }
        for (OrderLine line : order.getLines()) {
            Product product = line.getProduct();
            if (product.getStock() < line.getQuantity()) {
                throw BusinessException.conflict("在庫不足: " + product.getCode()
                        + " (在庫=" + product.getStock() + ", 要求=" + line.getQuantity() + ")");
            }
        }
        for (OrderLine line : order.getLines()) {
            Product product = line.getProduct();
            product.setStock(product.getStock() - line.getQuantity());
        }
        order.setStatus(OrderStatus.CONFIRMED);
        return toResponse(order);
    }

    @Transactional
    public OrderResponse cancelOrder(Long id) {
        Order order = findOrder(id);
        if (order.getStatus() == OrderStatus.INVOICED) {
            throw BusinessException.conflict("請求済みの注文はキャンセルできません: " + order.getOrderNo());
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw BusinessException.conflict("すでにキャンセル済みです: " + order.getOrderNo());
        }
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            for (OrderLine line : order.getLines()) {
                Product product = line.getProduct();
                product.setStock(product.getStock() + line.getQuantity());
            }
        }
        order.setStatus(OrderStatus.CANCELLED);
        return toResponse(order);
    }

    @Transactional
    public OrderResponse getOrder(Long id) {
        return toResponse(findOrder(id));
    }

    @Transactional
    public List<OrderResponse> listOrders(String status) {
        List<Order> orders = status == null || status.isBlank()
                ? orderRepository.findAll()
                : orderRepository.findByStatus(parseStatus(status));
        return orders.stream().map(this::toResponse).toList();
    }

    private OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("不明なステータスです: " + status);
        }
    }

    private Customer findCustomer(String code) {
        return customerRepository.findByCode(code)
                .orElseThrow(() -> BusinessException.notFound("顧客が見つかりません: " + code));
    }

    private Product findProduct(String code) {
        return productRepository.findByCode(code)
                .orElseThrow(() -> BusinessException.notFound("商品が見つかりません: " + code));
    }

    private Order findOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("注文が見つかりません: " + id));
    }

    private String newOrderNo() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private OrderResponse toResponse(Order order) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setOrderNo(order.getOrderNo());
        response.setCustomerCode(order.getCustomer().getCode());
        response.setCustomerName(order.getCustomer().getName());
        response.setOrderDate(order.getOrderDate());
        response.setStatus(OrderResponse.StatusEnum.valueOf(order.getStatus().name()));
        response.setTotalAmount(order.getTotalAmount());
        response.setLines(order.getLines().stream().map(this::toLineResponse).toList());
        return response;
    }

    private OrderLineResponse toLineResponse(OrderLine line) {
        OrderLineResponse response = new OrderLineResponse();
        response.setProductCode(line.getProduct().getCode());
        response.setProductName(line.getProduct().getName());
        response.setQuantity(line.getQuantity());
        response.setUnitPrice(line.getUnitPrice());
        response.setLineAmount(line.getLineAmount());
        return response;
    }
}
