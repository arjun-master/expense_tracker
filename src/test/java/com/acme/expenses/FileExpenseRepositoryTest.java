package com.acme.expenses;

import com.acme.expenses.model.Expense;
import com.acme.expenses.store.FileExpenseRepository;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class FileExpenseRepositoryTest {
    private FileExpenseRepositoryTest() {
    }

    static void persistsReloadsAndDeletesExpenses() throws Exception {
        Path directory = Files.createTempDirectory("expense-store-test");
        Path file = directory.resolve("expenses.csv");

        Expense original = new Expense(
                "expense-1",
                LocalDate.parse("2026-04-12"),
                "Dining",
                "Cafe, North \"Wing\"",
                new BigDecimal("37.45"),
                "client lunch",
                Instant.parse("2026-04-12T10:15:30Z")
        );

        FileExpenseRepository repository = new FileExpenseRepository(file);
        repository.save(original);

        FileExpenseRepository reloaded = new FileExpenseRepository(file);
        List<Expense> expenses = reloaded.findAll();
        Assertions.equals(1, expenses.size(), "Reloaded expense count");
        Assertions.equals(original, expenses.get(0), "Reloaded expense should match original");

        String csv = Files.readString(file);
        Assertions.contains(csv, "\"Cafe, North \"\"Wing\"\"\"", "CSV should quote commas and quotes");

        Assertions.isTrue(reloaded.deleteById("expense-1"), "Delete should report removal");
        Assertions.equals(0, reloaded.findAll().size(), "Deleted expense should be removed");
        Assertions.isTrue(!reloaded.deleteById("missing"), "Deleting missing expense should return false");
    }
}
