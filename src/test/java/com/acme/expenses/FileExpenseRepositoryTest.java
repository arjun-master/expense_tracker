package com.acme.expenses;

import com.acme.expenses.model.Expense;
import com.acme.expenses.store.FileExpenseRepository;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        Expense multiline = new Expense(
                "expense-2",
                LocalDate.parse("2026-04-13"),
                "Travel",
                "Rail",
                new BigDecimal("12.00"),
                "first line\nsecond line",
                Instant.parse("2026-04-13T08:00:00Z")
        );

        FileExpenseRepository repository = new FileExpenseRepository(file);
        repository.save(original);
        repository.save(multiline);

        FileExpenseRepository reloaded = new FileExpenseRepository(file);
        List<Expense> expenses = reloaded.findAll();
        Assertions.equals(2, expenses.size(), "Reloaded expense count");
        Assertions.equals(List.of(original, multiline), expenses, "Reloaded expenses should match originals");

        String csv = Files.readString(file);
        Assertions.contains(csv, "\"Cafe, North \"\"Wing\"\"\"", "CSV should quote commas and quotes");
        Assertions.contains(csv, "\"first line" + System.lineSeparator() + "second line\"", "CSV should preserve embedded newlines");

        Assertions.isTrue(reloaded.deleteById("expense-1"), "Delete should report removal");
        Assertions.equals(1, reloaded.findAll().size(), "Deleted expense should be removed");
        Assertions.equals(multiline, reloaded.findAll().get(0), "Remaining expense should stay intact");
        Assertions.isTrue(!reloaded.deleteById("missing"), "Deleting missing expense should return false");
    }

    static void coordinatesConcurrentSavesAcrossRepositoryInstances() throws Exception {
        Path directory = Files.createTempDirectory("expense-lock-test");
        Path file = directory.resolve("expenses.csv");

        FileExpenseRepository first = new FileExpenseRepository(file);
        FileExpenseRepository second = new FileExpenseRepository(file);
        Expense firstExpense = new Expense(
                "expense-1",
                LocalDate.parse("2026-04-12"),
                "Dining",
                "Cafe",
                new BigDecimal("11.25"),
                "one",
                Instant.parse("2026-04-12T10:15:30Z")
        );
        Expense secondExpense = new Expense(
                "expense-2",
                LocalDate.parse("2026-04-13"),
                "Travel",
                "Bus",
                new BigDecimal("7.50"),
                "two",
                Instant.parse("2026-04-13T11:15:30Z")
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> firstSave = executor.submit(() -> {
                await(start);
                first.save(firstExpense);
                return null;
            });
            Future<?> secondSave = executor.submit(() -> {
                await(start);
                second.save(secondExpense);
                return null;
            });

            start.countDown();
            firstSave.get(5, TimeUnit.SECONDS);
            secondSave.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        List<Expense> expenses = new FileExpenseRepository(file).findAll();
        Assertions.equals(2, expenses.size(), "Concurrent saves should persist both expenses");
        Assertions.isTrue(expenses.contains(firstExpense), "First expense should be present");
        Assertions.isTrue(expenses.contains(secondExpense), "Second expense should be present");
    }

    static void cleansUpTemporaryFilesWhenWriteAllFails() throws Exception {
        Path directory = Files.createTempDirectory("expense-temp-cleanup-test");
        Path file = directory.resolve("expenses.csv");
        FileExpenseRepository repository = new FileExpenseRepository(file);
        Expense expense = new Expense(
                "expense-1",
                LocalDate.parse("2026-04-12"),
                "Dining",
                "Cafe",
                new BigDecimal("11.25"),
                "one",
                Instant.parse("2026-04-12T10:15:30Z")
        );

        Method writeAll = FileExpenseRepository.class.getDeclaredMethod("writeAll", List.class, Runnable.class);
        writeAll.setAccessible(true);
        try {
            writeAll.invoke(repository, List.of(expense), (Runnable) () -> {
                throw new RuntimeException("forced failure");
            });
            throw new AssertionError("Expected writeAll to fail");
        } catch (InvocationTargetException exception) {
            Assertions.equals(RuntimeException.class, exception.getCause().getClass(), "Failure should be propagated");
        }

        long tempFiles;
        try (var stream = Files.list(directory)) {
            tempFiles = stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("expenses-") && name.endsWith(".csv");
                    })
                    .count();
        }
        Assertions.equals(0L, tempFiles, "Temporary files should be removed after failure");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to start", exception);
        }
    }
}
