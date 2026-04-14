package com.acme.expenses.service;

public record CreateExpenseRequest(
        String date,
        String category,
        String merchant,
        String amount,
        String notes
) {
}
