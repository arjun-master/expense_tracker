package com.acme.expenses.service;

import com.acme.expenses.model.Expense;
import com.acme.expenses.store.ExpenseRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ExpenseService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000.00");

    private final ExpenseRepository repository;
    private final Clock clock;

    public ExpenseService(ExpenseRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public ExpenseService(ExpenseRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Expense create(CreateExpenseRequest request) {
        Objects.requireNonNull(request, "request");
        LocalDate date = parseDate(request.date());
        String category = requireText(request.category(), "category", 40);
        String merchant = requireText(request.merchant(), "merchant", 80);
        String notes = optionalText(request.notes(), 240);
        BigDecimal amount = parseAmount(request.amount());

        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) {
            throw new ValidationException("Date cannot be in the future");
        }

        Expense expense = new Expense(
                UUID.randomUUID().toString(),
                date,
                toDisplayText(category),
                merchant,
                amount,
                notes,
                Instant.now(clock)
        );
        repository.save(expense);
        return expense;
    }

    public List<Expense> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(Expense::date).reversed()
                        .thenComparing(Expense::createdAt, Comparator.reverseOrder()))
                .toList();
    }

    public boolean delete(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("Expense id is required");
        }
        return repository.deleteById(id);
    }

    public ReportSummary summary() {
        List<Expense> expenses = list();
        BigDecimal total = expenses.stream()
                .map(Expense::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal average = expenses.isEmpty()
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : total.divide(BigDecimal.valueOf(expenses.size()), 2, RoundingMode.HALF_UP);

        Map<String, List<Expense>> categoryGroups = expenses.stream()
                .collect(Collectors.groupingBy(Expense::category, LinkedHashMap::new, Collectors.toList()));
        List<ReportSummary.CategoryBreakdown> byCategory = categoryGroups.entrySet().stream()
                .map(entry -> {
                    BigDecimal categoryTotal = sum(entry.getValue());
                    double percentage = total.signum() == 0
                            ? 0.0
                            : categoryTotal.multiply(BigDecimal.valueOf(100))
                                    .divide(total, 1, RoundingMode.HALF_UP)
                                    .doubleValue();
                    return new ReportSummary.CategoryBreakdown(
                            entry.getKey(),
                            categoryTotal,
                            entry.getValue().size(),
                            percentage
                    );
                })
                .sorted(Comparator.comparing(ReportSummary.CategoryBreakdown::total).reversed())
                .toList();

        Map<YearMonth, List<Expense>> monthGroups = new LinkedHashMap<>();
        for (Expense expense : expenses) {
            monthGroups.computeIfAbsent(YearMonth.from(expense.date()), ignored -> new ArrayList<>()).add(expense);
        }
        List<ReportSummary.MonthBreakdown> byMonth = monthGroups.entrySet().stream()
                .map(entry -> new ReportSummary.MonthBreakdown(
                        entry.getKey().toString(),
                        sum(entry.getValue()),
                        entry.getValue().size()
                ))
                .sorted(Comparator.comparing(ReportSummary.MonthBreakdown::month))
                .toList();

        return new ReportSummary(
                total,
                expenses.size(),
                average,
                byCategory,
                byMonth,
                expenses.stream().limit(8).toList()
        );
    }

    private static BigDecimal sum(List<Expense> expenses) {
        return expenses.stream()
                .map(Expense::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("Date is required");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new ValidationException("Date must use yyyy-mm-dd format");
        }
    }

    private static BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("Amount is required");
        }
        try {
            BigDecimal amount = new BigDecimal(value.trim()).setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) {
                throw new ValidationException("Amount must be greater than zero");
            }
            if (amount.compareTo(MAX_AMOUNT) > 0) {
                throw new ValidationException("Amount is too large");
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw new ValidationException("Amount must be a valid number");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        String cleaned = optionalText(value, maxLength);
        if (cleaned.isBlank()) {
            throw new ValidationException(capitalize(field) + " is required");
        }
        return cleaned;
    }

    private static String optionalText(String value, int maxLength) {
        String cleaned = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > maxLength) {
            throw new ValidationException("Text cannot exceed " + maxLength + " characters");
        }
        return cleaned;
    }

    private static String toDisplayText(String value) {
        String[] parts = value.toLowerCase(Locale.ROOT).split(" ");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            words.add(capitalize(part));
        }
        return String.join(" ", words);
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
