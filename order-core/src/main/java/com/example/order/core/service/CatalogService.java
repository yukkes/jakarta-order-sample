package com.example.order.core.service;

import com.example.order.core.repository.CustomerRepository;
import com.example.order.core.repository.ProductRepository;
import com.example.order.api.model.CustomerResponse;
import com.example.order.api.model.ProductResponse;
import com.example.order.core.web.paging.PageQuery;
import com.example.order.core.web.paging.PageResponse;
import com.example.order.domain.Customer;
import com.example.order.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class CatalogService {

    @Inject
    private ProductRepository productRepository;

    @Inject
    private CustomerRepository customerRepository;

    @Transactional
    public PageResponse<ProductResponse> listProducts(PageQuery page) {
        var products = productRepository.findPage(page.offset(), page.size(), page.sort());
        long total = productRepository.count();
        return PageResponse.of(products.stream().map(this::toProductResponse).toList(), page, total);
    }

    @Transactional
    public PageResponse<CustomerResponse> listCustomers(PageQuery page) {
        var customers = customerRepository.findPage(page.offset(), page.size(), page.sort());
        long total = customerRepository.count();
        return PageResponse.of(customers.stream().map(this::toCustomerResponse).toList(), page, total);
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
