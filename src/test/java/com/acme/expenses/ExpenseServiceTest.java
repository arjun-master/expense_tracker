package com.acme.expenses;

import com.acme.expenses.model.Expense;
import com.acme.expenses.service.CreateExpenseRequest;
import com.acme.expenses.service.ExpenseService;
import com.acme.expenses.service.ReportSummary;
import com.acme.expenses.service.ValidationException;
import com.acme.expenses.store.ExpenseRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class ExpenseServiceTest {
    private ExpenseServiceTest() {
    }

    static void createsAndSummarizesExpenses() {
        MemoryExpenseRepository repository = new MemoryExpenseRepository();
        ExpenseService service = new ExpenseService(
                repository,
                Clock.fixed(Instant.parse("2026-04-14T08:30:00Z"), ZoneOffset.UTC)
        );

        Expense groceries = service.create(new CreateExpenseRequest(
                "2026-04-10",
                " groceries ",
                " Market   Street ",
                "100.125",
                "weekly run"
        ));
        Expense transport = service.create(new CreateExpenseRequest(
                "2026-03-31",
                "transport",
                "Metro",
                "24.00",
                ""
        ));

        Assertions.equals("Groceries", groceries.category(), "Category should be display-cased");
        Assertions.equals("Market Street", groceries.merchant(), "Merchant whitespace should be normalized");
        Assertions.moneyEquals("100.13", groceries.amount(), "Amount should be rounded to cents");
        Assertions.equals(List.of(groceries, transport), service.list(), "Expenses should be sorted newest first");

        ReportSummary summary = service.summary();
        Assertions.equals(2, summary.count(), "Summary count");
        Assertions.moneyEquals("124.13", summary.total(), "Summary total");
        Assertions.moneyEquals("62.07", summary.average(), "Summary average");
        Assertions.equals("Groceries", summary.byCategory().get(0).category(), "Largest category should lead");
        Assertions.equals("2026-03", summary.byMonth().get(0).month(), "Months should be sorted ascending");
        Assertions.equals("2026-04", summary.byMonth().get(1).month(), "Months should include April");
    }

    static void rejectsInvalidExpenses() {
        ExpenseService service = new ExpenseService(
                new MemoryExpenseRepository(),
                Clock.fixed(Instant.parse("2026-04-14T08:30:00Z"), ZoneOffset.UTC)
        );

        ValidationException futureDate = Assertions.throwsType(
                ValidationException.class,
                () -> service.create(new CreateExpenseRequest("2026-04-15", "Food", "Cafe", "12.00", "")),
                "Future dates should be rejected"
        );
        Assertions.contains(futureDate.getMessage(), "future", "Future date message");

        Assertions.throwsType(
                ValidationException.class,
                () -> service.create(new CreateExpenseRequest("2026-04-01", "", "Cafe", "12.00", "")),
                "Blank category should be rejected"
        );
        Assertions.throwsType(
                ValidationException.class,
                () -> service.create(new CreateExpenseRequest("2026-04-01", "Food", "Cafe", "0", "")),
                "Zero amount should be rejected"
        );
        ValidationException scientificNotation = Assertions.throwsType(
                ValidationException.class,
                () -> service.create(new CreateExpenseRequest("2026-04-01", "Food", "Cafe", "1e10000000", "")),
                "Scientific notation should be rejected"
        );
        Assertions.contains(scientificNotation.getMessage(), "valid number", "Scientific notation message");

        ValidationException tooLongAmount = Assertions.throwsType(
                ValidationException.class,
                () -> service.create(new CreateExpenseRequest("2026-04-01", "Food", "Cafe", "123456789012345678901", "")),
                "Overly long amounts should be rejected"
        );
        Assertions.contains(tooLongAmount.getMessage(), "too long", "Long amount message");
    }

    private static final class MemoryExpenseRepository implements ExpenseRepository {
        private final List<Expense> expenses = new ArrayList<>();

        @Override
        public List<Expense> findAll() {
            return new ArrayList<>(expenses);
        }

        @Override
        public void save(Expense expense) {
            expenses.add(expense);
        }

        @Override
        public boolean deleteById(String id) {
            return expenses.removeIf(expense -> expense.id().equals(id));
        }
    }
}
