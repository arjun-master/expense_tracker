package com.acme.expenses.store;

import com.acme.expenses.model.Expense;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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

public final class FileExpenseRepository implements ExpenseRepository {
    private static final String HEADER = "id,date,category,merchant,amount,notes,createdAt";

    private final Path file;
    private final Path lockFile;
    private final Object monitor = new Object();

    public FileExpenseRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.lockFile = lockFileFor(file);
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
            if (Files.notExists(file)) {
                Files.writeString(file, HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
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
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Expense> expenses = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (i == 0 && line.equals(HEADER)) {
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                expenses.add(fromCsvRow(parseCsvLine(line)));
            }
            return expenses;
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read expenses", exception);
        }
    }

    private void writeAll(List<Expense> expenses) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent == null ? Path.of(".") : parent, "expenses-", ".csv");
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

    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (quoted) {
                if (character == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(character);
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        cells.add(current.toString());
        return cells;
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
