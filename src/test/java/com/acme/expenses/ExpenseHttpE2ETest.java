package com.acme.expenses;

import com.acme.expenses.http.ExpenseHttpServer;
import com.acme.expenses.service.ExpenseService;
import com.acme.expenses.store.FileExpenseRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExpenseHttpE2ETest {
    private ExpenseHttpE2ETest() {
    }

    static void exercisesApiFlow() throws Exception {
        Path directory = Files.createTempDirectory("expense-e2e-test");
        ExpenseService service = new ExpenseService(new FileExpenseRepository(directory.resolve("expenses.csv")));
        ExpenseHttpServer server = ExpenseHttpServer.create(0, service, Path.of("src/main/resources/static"));
        HttpClient client = HttpClient.newHttpClient();
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.port();

            HttpResponse<String> health = send(client, get(baseUrl + "/health"));
            Assertions.equals(200, health.statusCode(), "Health status");
            Assertions.contains(health.body(), "\"ok\"", "Health response");
            Assertions.equals("nosniff", health.headers().firstValue("x-content-type-options").orElse(""),
                    "API responses should include security headers");

            HttpResponse<String> created = send(client, post(baseUrl + "/api/expenses", """
                    {"date":"2026-04-13","category":"travel","merchant":"Rail Pass","amount":42.50,"notes":"airport transfer"}
                    """));
            Assertions.equals(201, created.statusCode(), "Create status");
            Assertions.contains(created.body(), "\"category\":\"Travel\"", "Created response category");
            String id = jsonString(created.body(), "id");

            HttpResponse<String> expenses = send(client, get(baseUrl + "/api/expenses"));
            Assertions.equals(200, expenses.statusCode(), "List status");
            Assertions.contains(expenses.body(), id, "List should include created id");

            HttpResponse<String> summary = send(client, get(baseUrl + "/api/reports/summary"));
            Assertions.equals(200, summary.statusCode(), "Summary status");
            Assertions.contains(summary.body(), "\"count\":1", "Summary should include count");
            Assertions.contains(summary.body(), "\"category\":\"Travel\"", "Summary should include category");

            HttpResponse<String> csv = send(client, get(baseUrl + "/api/export.csv"));
            Assertions.equals(200, csv.statusCode(), "CSV status");
            Assertions.contains(csv.body(), "Rail Pass", "CSV should include merchant");

            HttpResponse<String> page = send(client, get(baseUrl + "/"));
            Assertions.equals(200, page.statusCode(), "Index status");
            Assertions.contains(page.body(), "Expense Ledger", "Index page should render");
            Assertions.contains(page.body(), "filters.js", "Index should load filter feature");
            Assertions.contains(page.body(), "budgets.js", "Index should load budget feature");
            Assertions.contains(page.body(), "insights.js", "Index should load insights feature");
            Assertions.contains(page.headers().firstValue("content-security-policy").orElse(""),
                    "frame-ancestors 'none'", "Static responses should include CSP");

            assertStaticAsset(client, baseUrl, "/filters.js");
            assertStaticAsset(client, baseUrl, "/budgets.js");
            assertStaticAsset(client, baseUrl, "/insights.js");

            HttpResponse<String> badJson = send(client, post(baseUrl + "/api/expenses", "{bad json"));
            Assertions.equals(400, badJson.statusCode(), "Malformed JSON should return 400");

            HttpResponse<String> deleted = send(client, delete(baseUrl + "/api/expenses/" + id));
            Assertions.equals(204, deleted.statusCode(), "Delete status");

            HttpResponse<String> afterDelete = send(client, get(baseUrl + "/api/expenses"));
            Assertions.contains(afterDelete.body(), "\"expenses\":[]", "Deleted item should be absent");
        } finally {
            server.stop();
        }
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }

    private static HttpRequest post(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest delete(String url) {
        return HttpRequest.newBuilder(URI.create(url)).DELETE().build();
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertStaticAsset(HttpClient client, String baseUrl, String path) throws Exception {
        HttpResponse<String> response = send(client, get(baseUrl + path));
        Assertions.equals(200, response.statusCode(), "Static asset status for " + path);
        Assertions.contains(response.headers().firstValue("content-security-policy").orElse(""),
                "script-src 'self'", "Static asset should include CSP for " + path);
    }

    private static String jsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("Missing JSON field: " + field + " in " + json);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            throw new AssertionError("Unterminated JSON field: " + field + " in " + json);
        }
        return json.substring(start, end);
    }
}
