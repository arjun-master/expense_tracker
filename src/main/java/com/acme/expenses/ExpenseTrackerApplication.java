package com.acme.expenses;

import com.acme.expenses.http.ExpenseHttpServer;
import com.acme.expenses.service.ExpenseService;
import com.acme.expenses.store.FileExpenseRepository;

import java.io.IOException;
import java.nio.file.Path;

public final class ExpenseTrackerApplication {
    private ExpenseTrackerApplication() {
    }

    public static void main(String[] args) throws IOException {
        int port = readPort(args);
        Path dataFile = Path.of(System.getProperty("app.dataFile", "data/expenses.csv"));
        Path staticRoot = Path.of(System.getProperty("app.staticDir", "src/main/resources/static"));

        ExpenseService service = new ExpenseService(new FileExpenseRepository(dataFile));
        ExpenseHttpServer server = ExpenseHttpServer.create(port, service, staticRoot);
        server.start();

        System.out.printf("Expense tracker is running at http://127.0.0.1:%d/%n", server.port());
    }

    private static int readPort(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return parsePort(args[0]);
        }
        return parsePort(System.getProperty("app.port", "8080"));
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 0 || port > 65_535) {
                throw new IllegalArgumentException("Port must be between 0 and 65535");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid port: " + value, exception);
        }
    }
}
