package com.acme.expenses.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record Expense(
        String id,
        LocalDate date,
        String category,
        String merchant,
        BigDecimal amount,
        String notes,
        Instant createdAt
) {
    public Expense {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(notes, "notes");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
