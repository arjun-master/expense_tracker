package com.acme.expenses.store;

import com.acme.expenses.model.Expense;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FileExpenseRepository implements ExpenseRepository {
    private static final String HEADER = "id,date,category,merchant,amount,notes,createdAt";
    private static final List<String> HEADER_CELLS = List.of("id", "date", "category", "merchant", "amount", "notes", "createdAt");
    private static final ConcurrentMap<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private final Path file;
    private final Path lockFile;
    private final Object monitor;

    public FileExpenseRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.lockFile = lockFileFor(this.file);
        this.monitor = FILE_LOCKS.computeIfAbsent(this.file, ignored -> new Object());
        initialize();
    }

    @Override
    public List<Expense> findAll() {
        synchronized (monitor) {
            return readAll();
        }
    }

    @Override
    public void save(Expense expense) {
        Objects.requireNonNull(expense, "expense");
        withExclusiveFileLock(() -> {
            List<Expense> expenses = readAll();
            expenses.add(expense);
            writeAll(expenses);
            return null;
        });
    }

    @Override
    public boolean deleteById(String id) {
        return withExclusiveFileLock(() -> {
            List<Expense> expenses = readAll();
            boolean removed = expenses.removeIf(expense -> expense.id().equals(id));
            if (removed) {
                writeAll(expenses);
            }
            return removed;
        });
    }

    private void initialize() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                Files.writeString(
                        file,
                        HEADER + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW
                );
            } catch (FileAlreadyExistsException ignored) {
                // Another repository instance created the file first.
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to initialize expense store", exception);
        }
    }

    private List<Expense> readAll() {
        try {
            if (Files.notExists(file)) {
                return List.of();
            }
            List<List<String>> rows = parseCsvRows(Files.readString(file, StandardCharsets.UTF_8));
            List<Expense> expenses = new ArrayList<>();
            for (List<String> row : rows) {
                if (isBlankRow(row)) {
                    continue;
                }
                if (row.equals(HEADER_CELLS)) {
                    continue;
                }
                expenses.add(fromCsvRow(row));
            }
            return expenses;
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read expenses", exception);
        }
    }

    private void writeAll(List<Expense> expenses) {
        writeAll(expenses, null);
    }

    private void writeAll(List<Expense> expenses, Runnable afterTempCreated) {
        Path temp = null;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = Files.createTempFile(parent == null ? Path.of(".") : parent, "expenses-", ".csv");
            if (afterTempCreated != null) {
                afterTempCreated.run();
            }
            List<String> lines = new ArrayList<>();
            lines.add(HEADER);
            for (Expense expense : expenses) {
                lines.add(toCsvRow(expense));
            }
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write expenses", exception);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanupFailure) {
                    // Preserve the primary failure, if there was one.
                }
            }
        }
    }

    private <T> T withExclusiveFileLock(LockedOperation<T> operation) {
        synchronized (monitor) {
            try {
                Path parent = lockFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (FileChannel channel = FileChannel.open(
                        lockFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                ); FileLock ignored = channel.lock()) {
                    return operation.run();
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to lock expense store", exception);
            }
        }
    }

    public static String toCsv(List<Expense> expenses) {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Expense expense : expenses) {
            lines.add(toCsvRow(expense));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String toCsvRow(Expense expense) {
        return String.join(",",
                csvCell(expense.id()),
                csvCell(expense.date().toString()),
                csvCell(expense.category()),
                csvCell(expense.merchant()),
                csvCell(expense.amount().toPlainString()),
                csvCell(expense.notes()),
                csvCell(expense.createdAt().toString())
        );
    }

    private static Expense fromCsvRow(List<String> cells) {
        if (cells.size() != 7) {
            throw new IllegalStateException("Invalid expense row with " + cells.size() + " cells");
        }
        return new Expense(
                cells.get(0),
                LocalDate.parse(cells.get(1)),
                cells.get(2),
                cells.get(3),
                new BigDecimal(cells.get(4)),
                cells.get(5),
                Instant.parse(cells.get(6))
        );
    }

    private static String csvCell(String value) {
        boolean needsQuotes = value.contains(",")
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r");
        if (!needsQuotes) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static List<List<String>> parseCsvRows(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char character = content.charAt(i);
            if (quoted) {
                if (character == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(character);
                }
            } else {
                if (character == '"') {
                    if (current.length() != 0) {
                        throw new IllegalStateException("Unexpected quote in CSV row");
                    }
                    quoted = true;
                } else if (character == ',') {
                    currentRow.add(current.toString());
                    current.setLength(0);
                } else if (character == '\n') {
                    currentRow.add(current.toString());
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                    current.setLength(0);
                } else if (character == '\r') {
                    currentRow.add(current.toString());
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                    current.setLength(0);
                    if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                        i++;
                    }
                } else {
                    current.append(character);
                }
            }
        }
        if (quoted) {
            throw new IllegalStateException("Unterminated quoted CSV row");
        }
        currentRow.add(current.toString());
        rows.add(currentRow);
        return rows;
    }

    private static boolean isBlankRow(List<String> row) {
        return row.size() == 1 && row.get(0).isBlank();
    }

    private static Path lockFileFor(Path file) {
        Path filename = file.getFileName();
        if (filename == null) {
            return Path.of("expenses.csv.lock");
        }
        Path parent = file.getParent();
        String lockName = filename + ".lock";
        return parent == null ? Path.of(lockName) : parent.resolve(lockName);
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }
}
