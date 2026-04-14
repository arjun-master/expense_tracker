package com.acme.expenses.service;

import com.acme.expenses.model.Expense;

import java.math.BigDecimal;
import java.util.List;

public record ReportSummary(
        BigDecimal total,
        int count,
        BigDecimal average,
        List<CategoryBreakdown> byCategory,
        List<MonthBreakdown> byMonth,
        List<Expense> recent
) {
    public record CategoryBreakdown(String category, BigDecimal total, int count, double percentage) {
    }

    public record MonthBreakdown(String month, BigDecimal total, int count) {
    }
}
