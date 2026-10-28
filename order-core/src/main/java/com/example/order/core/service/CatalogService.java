package com.example.order.core.service;

import com.example.order.core.repository.CustomerRepository;
import com.example.order.core.repository.ProductRepository;
import com.example.order.api.model.CustomerResponse;
import com.example.order.api.model.ProductResponse;
import com.example.order.domain.Customer;
import com.example.order.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class CatalogService {

    @Inject
    private ProductRepository productRepository;

    @Inject
    private CustomerRepository customerRepository;

    @Transactional
    public List<ProductResponse> listProducts() {
        return productRepository.findAll().stream()
                .map(this::toProductResponse)
                .toList();
    }

    @Transactional
    public List<CustomerResponse> listCustomers() {
        return customerRepository.findAll().stream()
                .map(this::toCustomerResponse)
                .toList();
    }

    private ProductResponse toProductResponse(Product product) {
        ProductResponse response = new ProductResponse();
        response.setCode(product.getCode());
        response.setName(product.getName());
        response.setUnitPrice(product.getUnitPrice());
        response.setStock(product.getStock());
        return response;
    }

    private CustomerResponse toCustomerResponse(Customer customer) {
        CustomerResponse response = new CustomerResponse();
        response.setCode(customer.getCode());
        response.setName(customer.getName());
        response.setEmail(customer.getEmail());
        return response;
    }
}
