package com.acme.expenses.http;

import com.acme.expenses.model.Expense;
import com.acme.expenses.service.CreateExpenseRequest;
import com.acme.expenses.service.ExpenseService;
import com.acme.expenses.service.ValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ExpenseHttpServer implements AutoCloseable {
    private static final int MAX_REQUEST_BODY_BYTES = 64 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final ExpenseService service;

    private ExpenseHttpServer(HttpServer server, ExecutorService executor, ExpenseService service) {
        this.server = server;
        this.executor = executor;
        this.service = service;
    }

    public static ExpenseHttpServer create(int port, ExpenseService service, Path staticRoot) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
        ExpenseHttpServer applicationServer = new ExpenseHttpServer(server, executor, service);
        server.setExecutor(executor);
        server.createContext("/health", applicationServer::handleHealth);
        server.createContext("/api/expenses", applicationServer::handleExpenses);
        server.createContext("/api/reports/summary", applicationServer::handleSummary);
        server.createContext("/api/export.csv", applicationServer::handleCsvExport);
        server.createContext("/", new StaticFileHandler(staticRoot));
        return applicationServer;
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    private void handleExpenses(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("OPTIONS".equals(method)) {
                sendNoContent(exchange, 204);
                return;
            }
            if ("GET".equals(method) && "/api/expenses".equals(path)) {
                List<Expense> expenses = service.list();
                sendJson(exchange, 200, Json.expenseList(expenses));
                return;
            }
            if ("POST".equals(method) && "/api/expenses".equals(path)) {
                Map<String, String> body = Json.parseObject(readBody(exchange, MAX_REQUEST_BODY_BYTES));
                Expense expense = service.create(new CreateExpenseRequest(
                        body.get("date"),
                        body.get("category"),
                        body.get("merchant"),
                        body.get("amount"),
                        body.get("notes")
                ));
                sendJson(exchange, 201, Json.expense(expense));
                return;
            }
            if ("DELETE".equals(method) && path.startsWith("/api/expenses/")) {
                String id = path.substring("/api/expenses/".length());
                boolean deleted = service.delete(id);
                sendNoContent(exchange, deleted ? 204 : 404);
                return;
            }
            sendError(exchange, 404, "Route not found");
        } catch (RequestBodyTooLargeException exception) {
            sendError(exchange, 413, exception.getMessage());
        } catch (ValidationException | IllegalArgumentException exception) {
            sendError(exchange, 400, exception.getMessage());
        } catch (RuntimeException exception) {
            sendError(exchange, 500, "Unexpected server error");
        }
    }

    private void handleSummary(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        sendJson(exchange, 200, Json.summary(service.summary()));
    }

    private void handleCsvExport(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        byte[] bytes = csvForExpenses(service.list()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"expenses.csv\"");
        securityHeaders(exchange);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String readBody(HttpExchange exchange, int maxBytes) {
        try (InputStream input = exchange.getRequestBody();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new RequestBodyTooLargeException("Request body is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String csvForExpenses(List<Expense> expenses) {
        StringBuilder builder = new StringBuilder();
        builder.append("id,date,category,merchant,amount,notes,createdAt");
        for (Expense expense : expenses) {
            builder.append(System.lineSeparator())
                    .append(csvCell(expense.id())).append(',')
                    .append(csvCell(expense.date().toString())).append(',')
                    .append(csvCell(expense.category())).append(',')
                    .append(csvCell(expense.merchant())).append(',')
                    .append(csvCell(expense.amount().toPlainString())).append(',')
                    .append(csvCell(expense.notes())).append(',')
                    .append(csvCell(expense.createdAt().toString()));
        }
        return builder.append(System.lineSeparator()).toString();
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

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        securityHeaders(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Json.error(message));
    }

    private static void sendNoContent(HttpExchange exchange, int status) throws IOException {
        securityHeaders(exchange);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; connect-src 'self'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
    }

    private static final class RequestBodyTooLargeException extends RuntimeException {
        private RequestBodyTooLargeException(String message) {
            super(message);
        }
    }
}
