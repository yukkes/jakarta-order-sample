package com.example.order.core.service;

import com.example.order.core.repository.InvoiceRepository;
import com.example.order.api.model.InvoiceResponse;
import com.example.order.core.web.error.BusinessException;
import com.example.order.core.web.paging.PageQuery;
import com.example.order.core.web.paging.PageResponse;
import com.example.order.domain.Invoice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

@ApplicationScoped
public class InvoiceService {

    @Inject
    private InvoiceRepository invoiceRepository;

    @Transactional
    public PageResponse<InvoiceResponse> listInvoices(String month, PageQuery page) {
        String billingMonth = month == null || month.isBlank() ? null : normalizeMonth(month);
        var invoices = invoiceRepository.findPage(billingMonth, page.offset(), page.size(), page.sort());
        long total = invoiceRepository.count(billingMonth);
        return PageResponse.of(invoices.stream().map(this::toResponse).toList(), page, total);
    }

    @Transactional
    public InvoiceResponse getInvoice(Long id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("請求書が見つかりません: " + id));
        return toResponse(invoice);
    }

    private String normalizeMonth(String month) {
        try {
            return YearMonth.parse(month).toString();
        } catch (DateTimeParseException exception) {
            throw BusinessException.badRequest("月は YYYY-MM 形式で指定してください: " + month);
        }
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        InvoiceResponse response = new InvoiceResponse();
        response.setId(invoice.getId());
        response.setInvoiceNo(invoice.getInvoiceNo());
        response.setCustomerCode(invoice.getCustomer().getCode());
        response.setCustomerName(invoice.getCustomer().getName());
        response.setOrderNo(invoice.getOrder().getOrderNo());
        response.setBillingMonth(invoice.getBillingMonth());
        response.setTotalAmount(invoice.getTotalAmount());
        response.setTaxAmount(invoice.getTaxAmount());
        response.setCreatedAt(invoice.getCreatedAt());
        return response;
    }
}
